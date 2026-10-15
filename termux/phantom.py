#!/usr/bin/env python3
"""
PHANTOM SPECTRE listener - run inside Termux.

    pkg install python termux-api        # plus the Termux:API app from F-Droid
    python phantom.py                    # then open http://127.0.0.1:8765 on the phone

Serves phantom.html next to this file (installable as a home-screen app) and these JSON routes:

    /api/track          everything the app needs 3x/s: position, heading, wifi link, cell, latency, motion
    /api/scan           nearby access points (termux-wifi-scaninfo, cached 25 s; Android allows 4 scans / 2 min)
    /api/devices        live devices on the LAN: ping-triggered ARP sweep every 15 s, join/leave events (?since=seq)
    /api/devices/name   ?key=&name=   /api/devices/watch ?key=&on=1   /api/devices/forget ?key=   /api/devices/sweep
    /api/presence/config ?sweep_every=&grace=&notify=&scope=all|watched&join=&vibrate=
    /api/notify         ?title=&body=&id=   posts an Android notification through termux-notification
    /api/status         what works on this phone: sensors, tools, operator, uptime
    /api/track/set      ?x=&y=     relocate (tap on the map)
    /api/track/face     ?deg=      which map direction you are facing now (0=up, 90=right)
    /api/track/steplen  ?m=        stride in metres (0.3..1.2)
    /api/track/reset               position to 0,0

Bound to 127.0.0.1. PHANTOM_HOST=0.0.0.0 exposes it on the LAN, PHANTOM_PORT changes the port.
"""
import json
import math
import os
import re
import shutil
import statistics
import subprocess
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

PORT = int(os.environ.get("PHANTOM_PORT", "8765"))
HOST = os.environ.get("PHANTOM_HOST", "127.0.0.1")
HERE = os.path.dirname(os.path.abspath(__file__))
HTML = os.path.join(HERE, "phantom.html")
T0 = time.time()

lock = threading.Lock()
wifi = {"ok": False, "err": None}
cell = {"ok": False, "err": None}
net = {"latency_ms": None, "gateway": None, "iface": None, "ip": None, "subnet": None}
scan = {"items": [], "ts": 0.0}
hist = deque(maxlen=90)           # wifi rssi, ~1 Hz, for the motion detector
tools = {}
operator = {}

# ---------------- helpers ----------------

def have(cmd):
    return shutil.which(cmd) is not None


def run(cmd, timeout=6):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout).stdout
    except Exception:
        return ""


def run_json(cmd, timeout=6):
    out = run(cmd, timeout)
    try:
        return json.loads(out) if out.strip() else None
    except Exception:
        return None


# ---------------- dead reckoning ----------------
track = {
    "x": 0.0, "y": 0.0, "yaw": None, "offset": 0.0, "steplen": 0.7,
    "steps": 0, "step_base": None, "seq": 0,
    "sensors": {"rotation": False, "step": False, "accel": False},
    "src": "none", "err": None, "trail": deque(maxlen=400), "last_step_ts": 0.0,
}
tlock = threading.Lock()


def heading_nolock():
    if track["yaw"] is None:
        return track["offset"] % 360.0
    return (track["yaw"] + track["offset"]) % 360.0


def advance(n):
    with tlock:
        h = math.radians(heading_nolock())
        d = track["steplen"] * n
        track["x"] += d * math.sin(h)
        track["y"] -= d * math.cos(h)
        track["steps"] += n
        track["seq"] += 1
        track["last_step_ts"] = time.time()
        track["trail"].append((round(track["x"], 2), round(track["y"], 2)))


def quat_yaw(v):
    if len(v) < 3:
        return None
    x, y, z = v[0], v[1], v[2]
    w = v[3] if len(v) > 3 and v[3] != 0 else math.sqrt(max(0.0, 1.0 - (x * x + y * y + z * z)))
    yaw = math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z))
    return (-math.degrees(yaw)) % 360.0


class StepFilter:
    def __init__(self):
        self.buf = deque(maxlen=12)
        self.last_peak = 0.0


sf = StepFilter()


def handle_sensor(obj):
    rot = None; step_counter = None; step_det = False; accel = None
    for k, v in obj.items():
        kl = k.lower()
        vals = v.get("values") if isinstance(v, dict) else None
        if not vals:
            continue
        if "rotation vector" in kl:
            if rot is None or ("game" not in kl and "geomagnetic" not in kl):
                rot = vals
        elif "step counter" in kl:
            step_counter = vals[0]
        elif "step detector" in kl:
            step_det = True
        elif "accelerometer" in kl and "uncalibrated" not in kl:
            accel = vals
    now = time.time()
    with tlock:
        s = track["sensors"]
        s["rotation"] = s["rotation"] or rot is not None
        s["step"] = s["step"] or step_counter is not None or step_det
        s["accel"] = s["accel"] or accel is not None
        has_step_sensor = s["step"]
    if rot is not None:
        y = quat_yaw(rot)
        if y is not None:
            with tlock:
                track["yaw"] = y
    stepped = 0
    if step_counter is not None:
        with tlock:
            if track["step_base"] is None:
                track["step_base"] = step_counter
            delta = int(step_counter - track["step_base"])
            if delta > 0:
                track["step_base"] = step_counter
                stepped = min(delta, 3)
                track["src"] = "counter"
    elif step_det:
        stepped = 1
        with tlock:
            track["src"] = "detector"
    elif accel is not None and not has_step_sensor:
        mag = math.sqrt(sum(a * a for a in accel[:3]))
        sf.buf.append(mag)
        if len(sf.buf) >= 6:
            mean = sum(sf.buf) / len(sf.buf)
            prev, cur = sf.buf[-2], sf.buf[-1]
            if cur - mean > 1.6 and cur > prev and now - sf.last_peak > 0.32:
                stepped = 1
                sf.last_peak = now
                with tlock:
                    track["src"] = "accel"
    if stepped:
        advance(stepped)


def sensor_loop():
    try:
        subprocess.run(["termux-sensor", "-c"], capture_output=True, timeout=5)
        p = subprocess.Popen(["termux-sensor", "-s", "rotation vector,step,accelerometer", "-d", "120"],
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
    except Exception as e:
        with tlock:
            track["err"] = "termux-sensor unavailable: " + str(e)
        return
    buf, depth = [], 0
    for line in p.stdout:
        for ch in line:
            if ch == "{":
                depth += 1
            if depth > 0:
                buf.append(ch)
            if ch == "}":
                depth -= 1
                if depth == 0 and buf:
                    try:
                        obj = json.loads("".join(buf))
                    except Exception:
                        obj = None
                    buf = []
                    if isinstance(obj, dict):
                        handle_sensor(obj)
    with tlock:
        track["err"] = "termux-sensor stream ended"


def track_snapshot():
    with tlock:
        return {
            "x": round(track["x"], 2), "y": round(track["y"], 2),
            "heading": round(heading_nolock(), 1), "has_yaw": track["yaw"] is not None,
            "steps": track["steps"], "steplen": track["steplen"], "seq": track["seq"],
            "sensors": dict(track["sensors"]), "src": track["src"], "err": track["err"],
            "moving": (time.time() - track["last_step_ts"]) < 1.5,
            "trail": list(track["trail"])[-120:],
        }


# ---------------- wifi / cell / net ----------------

def motion():
    vals = [v for _, v in hist]
    if len(vals) < 10:
        return {"ready": False, "n": len(vals)}
    base = statistics.median(vals)
    recent = vals[-8:]
    dev = sum(abs(v - base) for v in recent) / len(recent)
    jitter = sum(abs(recent[i] - recent[i - 1]) for i in range(1, len(recent))) / (len(recent) - 1)
    score = min(1.0, max(dev, jitter) / 5.0)
    st = "still" if score < 0.3 else ("motion" if score < 0.7 else "strong")
    return {"ready": True, "baseline": base, "dev": round(dev, 2), "jitter": round(jitter, 2),
            "score": round(score, 2), "state": st, "history": vals[-60:]}


def wifi_loop():
    while True:
        info = run_json(["termux-wifi-connectioninfo"])
        with lock:
            if info is None and not have("termux-wifi-connectioninfo"):
                wifi.clear(); wifi.update({"ok": False, "err": "termux-api not installed: pkg install termux-api"})
                hist.clear()
            elif isinstance(info, dict) and isinstance(info.get("rssi"), int) and info.get("rssi") != -127 and info.get("rssi") < 0:
                ssid = (info.get("ssid") or "").strip('"')
                wifi.clear(); wifi.update({
                    "ok": True, "err": None,
                    "ssid": ssid or "(hidden)", "bssid": (info.get("bssid") or "").lower(),
                    "rssi": info.get("rssi"), "frequency_mhz": info.get("frequency_mhz"),
                    "link_speed_mbps": info.get("link_speed_mbps"), "ip": info.get("ip"), "ts": time.time()})
                hist.append((time.time(), info.get("rssi")))
            else:
                wifi.clear(); wifi.update({"ok": False, "err": "no wifi link, or Termux:API lacks Location permission"})
                hist.clear()
        time.sleep(0.8)


def cell_loop():
    global operator
    dev = run_json(["termux-telephony-deviceinfo"], 8)
    if isinstance(dev, dict):
        operator = {"name": dev.get("network_operator_name"), "type": dev.get("network_type"),
                    "data_state": dev.get("data_state")}
    while True:
        info = run_json(["termux-telephony-cellinfo"], 8)
        best = None
        if isinstance(info, list):
            regs = [c for c in info if isinstance(c, dict) and c.get("registered")]
            pool = regs or [c for c in info if isinstance(c, dict)]
            for c in pool:
                d = c.get("dbm")
                if isinstance(d, (int, float)) and -140 < d < 0:
                    if best is None or d > best["dbm"]:
                        best = {"dbm": int(d), "type": (c.get("type") or "?").upper(), "level": c.get("level"),
                                "asu": c.get("asu"), "registered": bool(c.get("registered")),
                                "cid": c.get("ci") or c.get("cid") or c.get("nci"), "pci": c.get("pci"), "tac": c.get("tac")}
        with lock:
            if best:
                cell.clear(); cell.update({"ok": True, "err": None, "ts": time.time(), "operator": operator.get("name"),
                                           "net_type": operator.get("type"), "neighbours": len(info) if isinstance(info, list) else 0, **best})
            else:
                err = ("termux-api not installed" if not have("termux-telephony-cellinfo")
                       else "no cell info: grant Termux:API Phone + Location permission, or no SIM")
                cell.clear(); cell.update({"ok": False, "err": err})
        time.sleep(2.0)


def net_loop():
    while True:
        route = run(["ip", "route"], 4)
        m = re.search(r"default via (\S+) dev (\S+)", route)
        gw = m.group(1) if m else None
        iface = m.group(2) if m else None
        ip = None; subnet = None
        if iface:
            a = run(["ip", "-4", "addr", "show", iface], 4)
            m2 = re.search(r"inet (\d+\.\d+\.\d+\.\d+)/(\d+)", a)
            if m2:
                ip = m2.group(1); subnet = f"{ip}/{m2.group(2)}"
        lat = None
        if gw:
            out = run(["ping", "-c", "1", "-W", "1", gw], 4)
            m3 = re.search(r"time[=<]([\d.]+)", out)
            if m3:
                lat = round(float(m3.group(1)), 1)
        with lock:
            net.update({"latency_ms": lat, "gateway": gw, "iface": iface, "ip": ip, "subnet": subnet})
        time.sleep(2.0)


def do_scan():
    with lock:
        if (time.time() - scan["ts"]) < 25 and scan["items"]:
            return scan["items"], round(time.time() - scan["ts"])
    res = run_json(["termux-wifi-scaninfo"], 12)
    items = []
    if isinstance(res, list):
        for r in res:
            if isinstance(r, dict) and r.get("bssid"):
                items.append({"ssid": r.get("ssid") or "(hidden)", "bssid": str(r.get("bssid")).lower(),
                              "rssi": r.get("rssi"), "frequency_mhz": r.get("frequency_mhz")})
    with lock:
        if items:
            scan["items"] = items; scan["ts"] = time.time()
        return scan["items"], round(time.time() - scan["ts"]) if scan["ts"] else None


# ---------------- presence engine (live devices, network fence) ----------------
PRES_FILE = os.path.join(HERE, "spectre_devices.json")
pres = {"devices": {}, "events": deque(maxlen=300), "seq": 0, "sweep_ts": 0.0, "sweeping": False, "err": None, "hosts": 0,
        "cfg": {"sweep_every": 15, "grace": 3, "notify": True, "scope": "all", "join": True, "vibrate": True}}
plock = threading.Lock()


def pres_load():
    try:
        with open(PRES_FILE) as f:
            j = json.load(f)
        for k, d in (j.get("devices") or {}).items():
            pres["devices"][k] = {"key": k, "ip": d.get("ip", ""), "mac": d.get("mac", ""), "host": d.get("host", ""), "name": d.get("name", ""),
                                  "watch": bool(d.get("watch")), "first_seen": d.get("first_seen"), "last_seen": d.get("last_seen"),
                                  "online": False, "missed": 99, "lat_ms": None, "joins": d.get("joins", 0)}
        pres["cfg"].update({k: v for k, v in (j.get("cfg") or {}).items() if k in pres["cfg"]})
    except Exception:
        pass


def pres_save():
    try:
        with plock:
            data = {"cfg": pres["cfg"], "devices": {k: {x: d.get(x) for x in ("ip", "mac", "host", "name", "watch", "first_seen", "last_seen", "joins")}
                                                   for k, d in pres["devices"].items()}}
        tmp = PRES_FILE + ".tmp"
        with open(tmp, "w") as f:
            json.dump(data, f)
        os.replace(tmp, PRES_FILE)
    except Exception:
        pass


def notify(title, body, nid="spectre", vibrate=True):
    if not have("termux-notification"):
        return False
    try:
        subprocess.Popen(["termux-notification", "--id", nid, "--title", title, "--content", body, "--priority", "high", "--sound"],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if vibrate and have("termux-vibrate"):
            subprocess.Popen(["termux-vibrate", "-d", "400"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except Exception:
        return False


def label(d):
    return d.get("name") or d.get("host") or d.get("ip") or d.get("mac") or "device"


def push_event(kind, d):
    with plock:
        pres["seq"] += 1
        ev = {"seq": pres["seq"], "t": time.time(), "type": kind, "key": d["key"], "label": label(d), "ip": d["ip"], "mac": d["mac"], "watch": bool(d.get("watch"))}
        pres["events"].append(ev)
        cfg = dict(pres["cfg"])
        watch = d.get("watch"); booted = pres.get("booted", False)
    if booted and cfg["notify"] and (cfg["scope"] == "all" or watch) and (kind == "leave" or cfg["join"]):
        notify("Phantom Spectre", f"{ev['label']} {'left the network' if kind == 'leave' else 'joined the network'}  ({d['ip']})",
               "spectre-" + re.sub(r"[^a-z0-9]", "", d["key"]), cfg["vibrate"])
    return ev


def ping_one(ip):
    out = run(["ping", "-c", "1", "-W", "1", ip], 4)
    m = re.search(r"time[=<]([\d.]+)", out)
    return ip, (round(float(m.group(1)), 1) if m else None)


def self_mac(iface):
    out = run(["ip", "link", "show", iface], 4) if iface else ""
    m = re.search(r"link/ether ([0-9a-f:]{17})", out)
    return m.group(1) if m else ""


def sweep():
    with plock:
        if pres["sweeping"]:
            return
        pres["sweeping"] = True
    try:
        with lock:
            subnet, gw, me, iface = net["subnet"], net["gateway"], net["ip"], net["iface"]
        if not subnet:
            with plock:
                pres["err"] = "not on a WiFi network"
            return
        base = me.rsplit(".", 1)[0]
        hosts = [f"{base}.{i}" for i in range(1, 255)]
        answered = {}
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=48) as ex:
            for ip, lat in ex.map(ping_one, hosts):
                if lat is not None:
                    answered[ip] = lat
        time.sleep(3.0)
        neigh = run(["ip", "neigh", "show"], 6)
        present, unknown = {}, set()
        for line in neigh.splitlines():
            m = re.match(r"(\d+\.\d+\.\d+\.\d+) dev (\S+)(?: lladdr (\S+))? (\S+)", line)
            if not m:
                continue
            ip, _, mac, st = m.groups()
            if not ip.startswith(base + "."):
                continue
            if st == "REACHABLE" and mac:
                present[ip] = mac.lower()
            elif st in ("STALE", "DELAY", "PROBE") and mac:
                unknown.add(ip)
                if ip in answered:
                    present[ip] = mac.lower()
        for ip in answered:
            present.setdefault(ip, "")
        if me:
            present[me] = self_mac(iface)
        now = time.time()
        events = []
        with plock:
            cfg = pres["cfg"]
            seen_keys = set()
            for ip, mac in present.items():
                key = mac or ("ip:" + ip)
                d = pres["devices"].get(key)
                if d is None and not mac:
                    d = next((x for x in pres["devices"].values() if x["ip"] == ip and x["mac"]), None)
                    if d:
                        key = d["key"]
                if d is None and mac and ("ip:" + ip) in pres["devices"]:
                    old = pres["devices"].pop("ip:" + ip)
                    d = dict(old); d["key"] = key; d["mac"] = mac
                    pres["devices"][key] = d
                if d is None:
                    d = {"key": key, "ip": ip, "mac": mac, "host": "", "name": "", "watch": False, "first_seen": now, "last_seen": now,
                         "online": False, "missed": 99, "lat_ms": None, "joins": 0}
                    pres["devices"][key] = d
                was = d["online"]
                d.update({"ip": ip, "mac": mac or d["mac"], "last_seen": now, "online": True, "missed": 0, "lat_ms": answered.get(ip)})
                d["me"] = ip == me; d["gateway"] = ip == gw
                if not was:
                    d["joins"] += 1
                    events.append(("join", dict(d)))
                seen_keys.add(key)
            for key, d in pres["devices"].items():
                if key in seen_keys or d["ip"] in unknown:
                    continue
                if d["online"]:
                    d["missed"] += 1
                    if d["missed"] >= cfg["grace"]:
                        d["online"] = False
                        events.append(("leave", dict(d)))
                else:
                    d["missed"] = min(d["missed"] + 1, 99)
            pres["sweep_ts"] = now; pres["err"] = None; pres["hosts"] = len(present)
        for kind, d in events:
            push_event(kind, d)
        with plock:
            pres["booted"] = True
        # hostnames for new devices (router DNS); cheap, 1 s timeout each
        import socket
        socket.setdefaulttimeout(1.0)
        for key, d in list(pres["devices"].items()):
            if d["online"] and not d["host"] and not d.get("_hosttry", 0) > now - 600:
                d["_hosttry"] = now
                try:
                    d["host"] = socket.gethostbyaddr(d["ip"])[0].split(".")[0]
                except Exception:
                    pass
        if events:
            pres_save()
    finally:
        with plock:
            pres["sweeping"] = False


def presence_loop():
    pres_load()
    while True:
        try:
            sweep()
        except Exception as e:
            with plock:
                pres["err"] = str(e)
        with plock:
            every = pres["cfg"]["sweep_every"]
        time.sleep(max(5, min(300, every)))


def presence_snapshot(since=0):
    with plock:
        items = []
        for d in pres["devices"].values():
            x = {k: v for k, v in d.items() if not k.startswith("_")}
            x["age_s"] = round(time.time() - d["last_seen"]) if d.get("last_seen") else None
            items.append(x)
        items.sort(key=lambda d: (not d["online"], [int(p) if p.isdigit() else 0 for p in (d["ip"] or "0.0.0.0").split(".")]))
        evs = [e for e in pres["events"] if e["seq"] > since][-100:]
        return {"ok": True, "items": items, "events": evs, "seq": pres["seq"], "online": sum(1 for d in items if d["online"]), "total": len(items),
                "sweep_age_s": round(time.time() - pres["sweep_ts"]) if pres["sweep_ts"] else None, "sweeping": pres["sweeping"], "err": pres["err"],
                "cfg": dict(pres["cfg"]), "notify_ok": have("termux-notification")}


def presence_brief():
    with plock:
        return {"online": sum(1 for d in pres["devices"].values() if d["online"]), "total": len(pres["devices"]), "seq": pres["seq"],
                "sweep_age_s": round(time.time() - pres["sweep_ts"]) if pres["sweep_ts"] else None}


def status():
    with lock:
        return {
            "ok": True, "uptime_s": int(time.time() - T0), "host": HOST, "port": PORT,
            "tools": {"termux_api": have("termux-wifi-connectioninfo"), "sensor": have("termux-sensor"),
                      "telephony": have("termux-telephony-cellinfo"), "notify": have("termux-notification"), "ping": have("ping")},
            "sensors": track_snapshot()["sensors"], "track_err": track["err"],
            "operator": operator, "net": dict(net),
        }


# ---------------- PWA bits ----------------
MANIFEST = {
    "name": "Phantom Spectre", "short_name": "Spectre", "start_url": "/", "scope": "/", "display": "standalone",
    "background_color": "#07090e", "theme_color": "#07090e",
    "icons": [{"src": "/icon.svg", "sizes": "any", "type": "image/svg+xml", "purpose": "any"}],
}
ICON_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128">
  <defs>
    <clipPath id="g"><path d="M64 20a32 32 0 0 1 32 32v46l-10.7-8-10.6 8-10.7-8-10.7 8-10.6-8L32 98V52a32 32 0 0 1 32-32z"/></clipPath>
    <radialGradient id="core" cx="64" cy="92" r="40" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#ff4fa3" stop-opacity=".95"/><stop offset=".5" stop-color="#ff4fa3" stop-opacity=".18"/><stop offset="1" stop-color="#ff4fa3" stop-opacity="0"/>
    </radialGradient>
  </defs>
  <rect width="128" height="128" rx="28" fill="#07090e"/>
  <rect width="128" height="128" rx="28" fill="none" stroke="#1a2535" stroke-width="2"/>
  <g clip-path="url(#g)">
    <rect width="128" height="128" fill="#0d1420"/>
    <rect width="128" height="128" fill="url(#core)"/>
    <g fill="none" stroke="#38f0dc" stroke-width="4.5" stroke-linecap="round">
      <circle cx="64" cy="92" r="14" opacity="1"/>
      <circle cx="64" cy="92" r="30" opacity=".75"/>
      <circle cx="64" cy="92" r="46" opacity=".5"/>
      <circle cx="64" cy="92" r="62" opacity=".3"/>
    </g>
  </g>
  <path d="M64 20a32 32 0 0 1 32 32v46l-10.7-8-10.6 8-10.7-8-10.7 8-10.6-8L32 98V52a32 32 0 0 1 32-32z" fill="none" stroke="#38f0dc" stroke-width="4" stroke-linejoin="round"/>
  <ellipse cx="52" cy="56" rx="5" ry="7" fill="#07090e"/><ellipse cx="76" cy="56" rx="5" ry="7" fill="#07090e"/>
  <circle cx="53.5" cy="54" r="2" fill="#38f0dc"/><circle cx="77.5" cy="54" r="2" fill="#38f0dc"/>
  <circle cx="64" cy="92" r="5" fill="#ff4fa3"/>
</svg>"""
SW_JS = """self.addEventListener('install',e=>self.skipWaiting());self.addEventListener('activate',e=>self.clients.claim());
self.addEventListener('fetch',e=>{const u=new URL(e.request.url);if(u.pathname.startsWith('/api/'))return;
e.respondWith(fetch(e.request).then(r=>{const c=r.clone();caches.open('spectre-v1').then(k=>k.put(e.request,c));return r;}).catch(()=>caches.match(e.request)));});"""


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body, ctype="application/json"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(data)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.end_headers()

    def do_GET(self):
        path, _, qs = self.path.partition("?")
        q = {k: v[0] for k, v in parse_qs(qs).items()}
        try:
            if path == "/api/track":
                body = track_snapshot()
                with lock:
                    body["wifi"] = dict(wifi); body["cell"] = dict(cell); body["net"] = dict(net)
                    body["motion"] = motion(); body["scan_age"] = round(time.time() - scan["ts"]) if scan["ts"] else None
                body["presence"] = presence_brief()
                body["ok"] = True
                self._send(200, body)
            elif path == "/api/scan":
                items, age = do_scan()
                self._send(200, {"ok": bool(items), "items": items, "age_s": age})
            elif path == "/api/devices":
                body = presence_snapshot(int(q.get("since", 0) or 0))
                with lock:
                    body["gateway"] = net["gateway"]; body["subnet"] = net["subnet"]
                self._send(200, body)
            elif path == "/api/devices/sweep":
                threading.Thread(target=sweep, daemon=True).start()
                self._send(200, {"ok": True})
            elif path in ("/api/devices/name", "/api/devices/watch", "/api/devices/forget"):
                key = q.get("key", "")
                with plock:
                    d = pres["devices"].get(key)
                    if d and path.endswith("name"):
                        d["name"] = q.get("name", "")[:40]
                    elif d and path.endswith("watch"):
                        d["watch"] = q.get("on", "1") == "1"
                    elif d and path.endswith("forget"):
                        del pres["devices"][key]
                pres_save()
                self._send(200, {"ok": bool(d)})
            elif path == "/api/presence/config":
                with plock:
                    c = pres["cfg"]
                    if "sweep_every" in q: c["sweep_every"] = max(5, min(300, int(q["sweep_every"])))
                    if "grace" in q: c["grace"] = max(1, min(20, int(q["grace"])))
                    if "notify" in q: c["notify"] = q["notify"] == "1"
                    if "scope" in q: c["scope"] = "watched" if q["scope"] == "watched" else "all"
                    if "join" in q: c["join"] = q["join"] == "1"
                    if "vibrate" in q: c["vibrate"] = q["vibrate"] == "1"
                    body = dict(c)
                pres_save()
                self._send(200, body)
            elif path == "/api/notify":
                ok = notify(q.get("title", "Phantom Spectre")[:60], q.get("body", "")[:200], "spectre-" + re.sub(r"[^a-z0-9]", "", q.get("id", "app")), True)
                self._send(200, {"ok": ok})
            elif path == "/api/status":
                self._send(200, status())
            elif path == "/api/track/set":
                with tlock:
                    track["x"] = float(q.get("x", track["x"])); track["y"] = float(q.get("y", track["y"]))
                    track["seq"] += 1; track["trail"].clear()
                    track["trail"].append((round(track["x"], 2), round(track["y"], 2)))
                self._send(200, track_snapshot())
            elif path == "/api/track/face":
                want = float(q.get("deg", 0))
                with tlock:
                    track["offset"] = (want - (track["yaw"] or 0.0)) % 360.0
                self._send(200, track_snapshot())
            elif path == "/api/track/steplen":
                with tlock:
                    track["steplen"] = max(0.3, min(1.2, float(q.get("m", 0.7))))
                self._send(200, track_snapshot())
            elif path == "/api/track/reset":
                with tlock:
                    track["x"] = track["y"] = 0.0; track["steps"] = 0; track["seq"] += 1; track["trail"].clear()
                self._send(200, track_snapshot())
            elif path == "/manifest.webmanifest":
                self._send(200, json.dumps(MANIFEST).encode(), "application/manifest+json")
            elif path == "/icon.svg":
                self._send(200, ICON_SVG.encode(), "image/svg+xml")
            elif path == "/sw.js":
                self._send(200, SW_JS.encode(), "application/javascript")
            elif path in ("/", "/index.html", "/phantom.html"):
                if os.path.exists(HTML):
                    with open(HTML, "rb") as f:
                        self._send(200, f.read(), "text/html; charset=utf-8")
                else:
                    self._send(404, {"error": "phantom.html not found next to phantom.py"})
            else:
                self._send(404, {"error": "not found"})
        except ValueError:
            self._send(400, {"error": "bad parameter"})

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    for fn in (wifi_loop, cell_loop, net_loop, sensor_loop, presence_loop):
        threading.Thread(target=fn, daemon=True).start()
    srv = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"PHANTOM SPECTRE listener on http://{HOST}:{PORT}  (Ctrl+C to stop)")
    print("tools:", ", ".join(f"{k}={'yes' if v else 'no'}" for k, v in status()["tools"].items()))
    print(f"serving {'phantom.html' if os.path.exists(HTML) else 'API only - phantom.html missing'}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        subprocess.run(["termux-sensor", "-c"], capture_output=True)
