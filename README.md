# BeamBridge

Turns an Android phone with an IR blaster into a local-network IR gateway.

- **App**: Material 3 Expressive UI. Pair a TV, AC or set-top box with a test wizard, keep custom buttons, copy any button's URL.
- **Gateway**: embedded HTTP server on the phone's LAN IP (default port 8080), run by a foreground service with a Wi-Fi lock and an optional partial wake lock.

## Build

```
./gradlew :core:test            # IR encoders, dataset parsers, HTTP server (runs on any JDK 17+)
./gradlew :app:assembleDebug    # needs the Android SDK (Android Studio sets this up)
```

The APK is at `app/build/outputs/apk/debug/`. The GitHub Actions workflow in `.github/workflows/android.yml`
runs the same steps and uploads the debug and release APKs as artifacts.

## API

Everything the app can do is also available over HTTP, and the web UI at `/` uses only these routes. With
password mode on (Settings), every route needs HTTP Basic auth.

| Method | Path | Params | Notes |
|---|---|---|---|
| GET | `/api/status` | | status, emitter present, base URL |
| GET/POST | `/api/blast` | `device`, `button` | press a key |
| POST | `/api/raw` | `ir` free text (`NEC 0x20DF10EF 32`, raw µs list, Pronto), or `protocol`+`code`[+`bits`], `pronto`, `frequency`+`pattern` | send any code |
| GET/POST | `/api/ac` | `device`, optional `power` `mode` `temp` `fan` | new AC state |
| GET / POST | `/api/devices` | POST: `name`, optional `type`, `button`, `ir` | list / create custom device |
| GET / POST / DELETE | `/api/devices/{id}` | POST: `name`, `archived` | read, rename/archive, delete |
| POST / DELETE | `/api/devices/{id}/buttons[/{name}]` | `name`+`ir` (add/edit), `hidden=true/false`, `cell=N` (move on grid) | edit keys |
| GET | `/api/catalog/brands` | `type`, `q` | pairing: brands |
| GET | `/api/catalog/candidates` | `type`, `brand`, `on` | pairing: codes to try |
| POST | `/api/catalog/test` | `type`, `brand`, `index`, `on` | pairing: send the test key |
| GET | `/api/finder`, `/api/finder/keys` | | TV finder state, keys |
| POST | `/api/finder/{start,stop,resume,page,test,accept,reset}` | `key`, `page`, `index`, `device`, `name` | TV finder |
| GET / POST | `/api/settings` | `authMode`, `username`, `password`, `bindAddress`, `port`, `wakeLock`, `autoStart` | password is never returned |
| GET | `/api/network`, `/api/network/check` | `address`, `port` | bind options, port availability |

AC devices take `POWER_ON`, `POWER_OFF`, `TEMP_UP`, `TEMP_DOWN`, `MODE_*` and `FAN_*` through `/api/blast`.

## Datasets (`app/src/main/assets`)

`tv_remotes.json`, `ac_remotes.json`, `ir_protocols.json`, `lcndb.db` (provider remotes for set-top boxes).
Notes from reading the real files:

- `ir_protocols.json` holds protocol *programs*: a small bytecode that renders a code into pulses. `IrProgram`
  decodes 154 of 156. It is used for AC frames and for TV/set-top remotes with `PROTOCOL_n` names.
- AC remotes store a full POWER frame plus XOR deltas for mode, temperature and fan. `AcCodec` XORs them.
  **This is unverified on a real AC.** Checksums for non-default modes and remotes with `rules` set may be wrong.
- Some TV/set-top protocols (ids 3, 4, 5, 10, 17, 18 in `lcndb.db`) have no encoder yet and are hidden from the wizard.
