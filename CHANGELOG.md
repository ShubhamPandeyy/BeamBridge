# Changelog

The section matching the current `baseVersion` is placed at the top of each GitHub Release's notes; the commit list is appended automatically (see `.github/workflows/android.yml`).

## 1.7
Control your devices from the iPhone's built-in Apple TV Remote.

**iPhone remote**
- Every device appears as its **own Apple TV** in Control Center → Apple TV Remote, so one iPhone controls the TV, the AC and the set-top box separately.
- **Teach it gestures:** open a device → ⋮ → *Set up iOS remote*. The screen listens, you swipe or press on the iPhone, pick the button it should send, and save. Gestures you don't set up do nothing.
- Touchpad strokes and buttons are separate: *Swipe up* and the *Up* button can send different keys. Gesture ids all start with `ios_` (`ios_swipe_up`, `ios_tap`, `ios_up`, `ios_select`, `ios_back`, `ios_tv`, `ios_play_pause`, `ios_volume_up`, `ios_mute`, `ios_power`…). AC devices can map gestures to AC actions such as Temp + or Mode cool.
- **PIN pairing** with a PIN you choose (a random one is made on first start). *Only paired iPhones* is on by default, and *Forget all* makes every iPhone enter the PIN again.
- Its own **on/off switch** on the home screen next to the server, with the PIN, how many devices are shown and the last gesture. Optional auto start. Choose which devices are shown in Settings → *iPhone remote*.
- Built on a Kotlin port of [atv-core](https://github.com/corvofeng/atv-core) (MIT), tested against pyatv. Unlike the original it checks the iPhone's pairing keys and uses fresh keys per connection. No Rust or NDK needed.
- The web API's device JSON now includes `ios_gestures`.

**Releases**
- Tags like `v1.7` now get their CHANGELOG notes (the workflow used to look for a section named "1").
- README's release section rewritten to say exactly what each kind of build publishes.

## 1.6
Fixes everything from the project audit.

**IR codes**
- TV and set-top "PROTOCOL_n" codes were sent with **air-conditioner** waveforms, because the two datasets number protocols differently. `PROTOCOL_12/13` (TCL, VU, Micromax, Akai, IGO, Thomson) are now sent as RCA, and set-top `5/10` as RC5. Saved devices pick this up without re-pairing.
- Protocol numbers with no known definition (Xiaomi MI TV, Onida, Godrej, Nakamichi, Sharp and some set-top boxes) are hidden from pairing and the finder instead of firing a wrong signal. Use the TV finder for these.

**Reliability**
- Crash-safe saves (temp file, fsync, rename). A file that can't be read is kept aside as `.corrupt-<time>` instead of being overwritten with an empty list.
- The API answers 503 "still loading" until the code database is ready, and the remote screen refreshes once it is.
- Scenes run one at a time, so their keys never interleave. `wait=false` returns immediately.
- History is saved in batches off the send path; the "beamed" notification only appears when the app isn't on screen.
- The finder remembers who started it; the web asks before taking over a run on the phone (API: 409 / `force=true`).

**Security**
- Requests a browser marks as coming from another website can no longer send IR or change settings. The wildcard CORS header is gone.
- The web password is stored as a salted PBKDF2 hash (existing passwords are converted on first start).
- New setting: *Allow actions from simple GET links* (on by default for existing automations).
- Over the lock screen the remote only sends keys. Editing, deleting and copying URLs need an unlock.

**Fixes**
- "I don't know the brand" no longer leaves an empty "My TV" behind when you back out.
- Deleting a device removes its steps from scenes.
- Tile moves are limited to the grid's rows. Booleans in the API are strict everywhere (400 on typos).
- Settings show a shortcut when notifications are blocked. The dashboard Wi-Fi name follows network changes.
- Settings no longer overwrite app preferences changed while the sheet was open.

**Releases**
- GitHub Actions bumped to v5 (Node 24; removes the Node.js 20 deprecation warning).
- Pushes to `main` refresh one *nightly* pre-release. *Run workflow* or a `v*` tag publishes a real release.

**Cleanup**
- Removed the old 4-column key grid (data field, API `cell`, CSS), unused code, `ir_protocols.json`, and `lcndb.db`. The 1.9 MB SQLite database, previously copied into app storage, is replaced by a 320 KB `stb_remotes.json`.
- Faster API responses: the layout is computed once per device, AC remotes and brands are indexed, and the dashboard HTML is cached.

## 1.5
- **Onboarding:** "Allow notifications" now works reliably. If Android has already blocked the dialog (denied before), the button becomes **Open notification settings**, and the card refreshes when you come back.
- **Lock screen:** the open remote now shows over the lock screen by default and wakes the screen, so you can control IR devices without unlocking. Existing installs are switched on once; you can turn it off in Settings.
- **IR codes:** verified the whole bundled TV dataset (3,021 codes): every NEC / Samsung code decodes back to its dataset bits, and every pattern ends even-length with a trailing space for Qualcomm IR HALs. New regression tests guard this.
- **Releases:** every build on `main` now publishes an installable APK to GitHub Releases with these notes.

## 1.4.0
- IR compatibility fix for Qualcomm phones (Poco / Redmi / Xiaomi): even-length patterns, carrier-cycle NEC/Samsung timings, AC frame width.
- Movable and resizable remote grid (5 × 8 default, customisable) in the app and web dashboard.
- Optional location permission (Wi-Fi name only) with an onboarding explainer; new README.

## 1.3.0
- Command history, scenes & macros, "beamed" notification, first-run onboarding.
- Dashboard, remote, AC dial and pairing improvements; web dashboard connection bar, search, API playground.
- Fixes: live wake lock, case-insensitive IDs, strict settings and AC validation, safe auto-bind, toggle bit only after a successful send.

## 1.2.0
- Default remote of fixed keys with *Add keys to screen*, swipe D-pad, phone volume keys, lock-screen mode, Material 3 colour themes.

## 1.1.0
- Web UI parity with the app, TV finder, archive / hide / move, web access settings, TCL RCA codes.

## 1.0.0
- Core engine, HTTP server, Compose app, GitHub Actions build.
