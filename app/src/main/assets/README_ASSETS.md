# Reference datasets

The app reads these from this folder at runtime; replace a file to update the catalog.

- `tv_remotes.json`: TV remotes (hex codes + TV protocol number).
- `extra_tv_remotes.json`: extra TV remotes merged by brand (TCL RCA).
- `ac_remotes.json`: AC remotes, each with its own protocol program.
- `stb_remotes.json`: DTH / cable providers and their remotes, extracted once from the original `lcndb.db`
  (tables providers, provider_remotes, remotes, key_codes). The rest of that 1.9 MB database was unused.
