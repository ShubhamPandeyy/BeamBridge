# Third-party notices

## atv-core (MIT)
The iPhone remote (`core/src/main/kotlin/app/beambridge/core/atv/`) is a Kotlin port of the protocol code in
[corvofeng/atv-core](https://github.com/corvofeng/atv-core), which declares the MIT license (`license = "MIT"` in its
Cargo.toml and README; the repository has no separate LICENSE file). atv-core is in turn derived from pyatv's
`fake_atv.py` (MIT, https://github.com/postlund/pyatv).

## BouncyCastle (MIT)
Ed25519, X25519, ChaCha20-Poly1305 and HKDF come from `org.bouncycastle:bcprov-jdk18on`
(Bouncy Castle License, an MIT-style license, https://www.bouncycastle.org/licence.html).

## Roboto Flex (SIL Open Font License 1.1)
`app/src/main/res/font/roboto_flex.ttf` is the Latin subset of Roboto Flex by Font Bureau and Google
(https://github.com/googlefonts/roboto-flex), licensed under the SIL Open Font License, Version 1.1
(https://openfontlicense.org).

## Other dependencies
NanoHTTPD (BSD-3-Clause), kotlinx.coroutines / kotlinx.serialization / Kotlin (Apache-2.0), AndroidX and Jetpack Compose (Apache-2.0).
