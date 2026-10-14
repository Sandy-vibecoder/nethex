# Phantom Spectre — Android app

Native Android build of the Phantom Spectre survey console. A foreground service replaces the Termux
listener (WiFi, cell, step/rotation sensors, device presence sweep, Android notifications) and serves the
same `phantom.html` to a WebView on `127.0.0.1:8765`. No Termux needed once installed.

## Build it with only a phone (GitHub does the compiling)

You need a free GitHub account. Everything below runs in Termux.

```bash
pkg install git gh unzip
unzip ~/storage/downloads/spectre-android.zip -d ~/spectre-android
cd ~/spectre-android
git init -b main && git add . && git commit -m "Phantom Spectre"
gh auth login            # choose GitHub.com → HTTPS → login with a web browser, paste the code
gh repo create phantom-spectre --private --source=. --push
```

The push starts the build (about 4–6 minutes). Watch it:

```bash
gh run watch
```

When it finishes, download the APK and install it:

```bash
gh release download latest -p PhantomSpectre.apk --clobber
termux-open PhantomSpectre.apk
```

Android will ask to allow installs from Termux the first time. The APK is also on the repo's
**Releases** page in a browser: `https://github.com/<you>/phantom-spectre/releases/latest`.

Every later `git push` rebuilds and replaces the APK in the same release. The signing key is in the repo
(`app/spectre.keystore`), so updates install over the previous version.

## First run

1. Grant Location (WiFi names and scans need it), Physical activity (step counter), Phone (cell signal) and
   Notifications.
2. The listener notification "Listener running" appears; the app works in the background as long as it is
   there. Exclude the app from battery optimisation if sweeps stop when the screen is off.
3. Devices tab → Network fence and Map fence are editable at any time; settings persist.

## What is in the APK

| File | Role |
|---|---|
| `SpectreService.kt` | Foreground service, notification channels, wake lock |
| `SpectreServer.kt` | HTTP API on 127.0.0.1:8765, same routes as `phantom.py` |
| `Telemetry.kt` | WiFi link, cell dBm, gateway ping, AP scans, motion detector |
| `Tracker.kt` | Dead reckoning from rotation vector + step detector |
| `Presence.kt` | Ping-triggered ARP sweep, join/leave events, names, watch flags, notifications |
| `MainActivity.kt` | Permissions, WebView |
| `assets/phantom.html` | The app UI |

Edit `assets/phantom.html` to change the UI; push to rebuild.
