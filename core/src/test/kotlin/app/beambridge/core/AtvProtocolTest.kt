package app.beambridge.core

import app.beambridge.core.atv.*
import kotlin.test.*

/**
 * Byte-exact vectors from atv-core's test suite (generated there from pyatv's Python reference), so the Kotlin port
 * provably matches the protocol an iPhone speaks.
 */
class AtvProtocolTest {
    private fun h(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun hex(s: String) = s

    // ---- OPACK ----
    private fun I(v: ULong) = OValue.Int(v.toLong())
    private fun S(v: String) = OValue.Str(v)
    private fun b(x: String) = OValue.Bytes(h(x))
    private fun u(x: String) = OValue.Uuid(h(x))
    private fun arr(x: List<OValue>) = OValue.Arr(x)
    private fun p(k: OValue, v: OValue) = k to v
    private fun dct(x: List<Pair<OValue, OValue>>) = OValue.Dict(x)

    private fun check(value: OValue, expectedHex: String) {
        assertEquals(expectedHex, hex(Opack.pack(value)), "pack")
        val (back, used) = Opack.unpack(h(expectedHex))
        assertEquals(expectedHex.length / 2, used, "consumed")
        assertEquals(value, back, "unpack")
    }

    @Test fun opack_none() = check(OValue.Null, "04")
    @Test fun opack_bools() = check(arr(listOf(OValue.Bool(true), OValue.Bool(false))), "d20102")
    @Test fun opack_small_ints() = check(arr(listOf(I(0UL), I(1UL), I(39UL))), "d308092f")
    @Test fun opack_int_boundaries() = check(arr(listOf(I(40UL), I(255UL), I(256UL), I(65535UL), I(65536UL), I(4294967295UL), I(4294967296UL), I(18446744073709551615UL))), "d8302830ff31000131ffff320000010032ffffffff33000000000100000033ffffffffffffffff")
    @Test fun opack_floats() = check(arr(listOf(OValue.Dbl(0.5), OValue.Dbl(-1.25), OValue.Dbl(3.141592653589793), OValue.Dbl(1e+300))), "d436000000000000e03f36000000000000f4bf36182d4454fb210940369c7500883ce4377e")
    @Test fun opack_strings() = check(arr(listOf(S(""), S("a"), S("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"), S("yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy"), S("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"), S("wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww"), S("遥控器🔥"), S("quote\"back\\slash"))), "d8404161607878787878787878787878787878787878787878787878787878787878787878612179797979797979797979797979797979797979797979797979797979797979797961ff7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a7a620001777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777777774de981a5e68ea7e599a8f09f94a55071756f7465226261636b5c736c617368")
    @Test fun opack_bytes() = check(arr(listOf(b(""), b("00"), b("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"), b("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"), b("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"))), "d570710090000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f9121000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20920002000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
    @Test fun opack_uuid() = check(u("5d797fd33538427ea47ba32fc6cf3a6a"), "055d797fd33538427ea47ba32fc6cf3a6a")
    @Test fun opack_array_simple() = check(arr(listOf(I(1UL), S("two"), b("03"), OValue.Null, OValue.Bool(true), OValue.Dbl(2.5))), "d6094374776f71030401360000000000000440")
    @Test fun opack_array_endless() = check(arr(listOf(I(0UL), I(1UL), I(2UL), I(3UL), I(4UL), I(5UL), I(6UL), I(7UL), I(8UL), I(9UL), I(10UL), I(11UL), I(12UL), I(13UL), I(14UL), I(15UL), I(16UL), I(17UL), I(18UL), I(19UL))), "df08090a0b0c0d0e0f101112131415161718191a1b03")
    @Test fun opack_nested() = check(dct(listOf(p(S("a"), arr(listOf(I(1UL), dct(listOf(p(S("b"), arr(listOf(I(2UL), dct(listOf(p(S("c"), OValue.Null)))))))), S("not-a-tuple")))), p(S("d"), dct(listOf(p(S("e"), arr(listOf(dct(listOf(p(S("f"), OValue.Dbl(1.5)))))))))))), "e24161d309e14162d20ae14163044b6e6f742d612d7475706c654164e14165d1e1416636000000000000f83f")
    @Test fun opack_dict_simple() = check(dct(listOf(p(S("_i"), S("_hidC")), p(S("_t"), I(2UL)), p(S("_c"), dct(listOf(p(S("_hidC"), I(1UL)), p(S("_hBtS"), I(1UL))))), p(S("_x"), I(12345UL)))), "e4425f69455f68696443425f740a425f63e2a109455f6842745309425f78313930")
    @Test fun opack_dict_endless() = check(dct(listOf(p(S("key00"), I(0UL)), p(S("key01"), I(7UL)), p(S("key02"), I(14UL)), p(S("key03"), I(21UL)), p(S("key04"), I(28UL)), p(S("key05"), I(35UL)), p(S("key06"), I(42UL)), p(S("key07"), I(49UL)), p(S("key08"), I(56UL)), p(S("key09"), I(63UL)), p(S("key10"), I(70UL)), p(S("key11"), I(77UL)), p(S("key12"), I(84UL)), p(S("key13"), I(91UL)), p(S("key14"), I(98UL)), p(S("key15"), I(105UL)))), "ef456b6579303008456b657930310f456b6579303216456b657930331d456b6579303424456b657930352b456b65793036302a456b657930373031456b657930383038456b65793039303f456b657931303046456b65793131304d456b657931323054456b65793133305b456b657931343062456b65793135306903")
    @Test fun opack_object_ref_short() = check(dct(listOf(p(S("k1"), S("repeated-string-value")), p(S("k2"), S("repeated-string-value")))), "e2426b315572657065617465642d737472696e672d76616c7565426b32a1")
    @Test fun opack_object_ref_nested() = check(arr(listOf(arr(listOf(S("shared bytes object"), S("shared bytes object"), S("shared bytes object"))), dct(listOf(p(S("x"), S("shared bytes object")))))), "d2d353736861726564206279746573206f626a656374a0a0e14178a0")
    @Test fun opack_object_ref_long_index() = check(dct(listOf(p(S("k00"), S("distinct-value-00")), p(S("k01"), S("distinct-value-01")), p(S("k02"), S("distinct-value-02")), p(S("k03"), S("distinct-value-03")), p(S("k04"), S("distinct-value-04")), p(S("k05"), S("distinct-value-05")), p(S("k06"), S("distinct-value-06")), p(S("k07"), S("distinct-value-07")), p(S("k08"), S("distinct-value-08")), p(S("k09"), S("distinct-value-09")), p(S("k10"), S("distinct-value-10")), p(S("k11"), S("distinct-value-11")), p(S("k12"), S("distinct-value-12")), p(S("k13"), S("distinct-value-13")), p(S("k14"), S("distinct-value-14")), p(S("k15"), S("distinct-value-15")), p(S("k16"), S("distinct-value-16")), p(S("k17"), S("distinct-value-17")), p(S("k18"), S("distinct-value-18")), p(S("k19"), S("distinct-value-19")), p(S("k20"), S("distinct-value-20")), p(S("k21"), S("distinct-value-21")), p(S("k22"), S("distinct-value-22")), p(S("k23"), S("distinct-value-23")), p(S("k24"), S("distinct-value-24")), p(S("k25"), S("distinct-value-25")), p(S("k26"), S("distinct-value-26")), p(S("k27"), S("distinct-value-27")), p(S("k28"), S("distinct-value-28")), p(S("k29"), S("distinct-value-29")), p(S("k30"), S("distinct-value-30")), p(S("k31"), S("distinct-value-31")), p(S("k32"), S("distinct-value-32")), p(S("k33"), S("distinct-value-33")), p(S("k34"), S("distinct-value-34")), p(S("k35"), S("distinct-value-35")), p(S("k36"), S("distinct-value-36")), p(S("k37"), S("distinct-value-37")), p(S("k38"), S("distinct-value-38")), p(S("k39"), S("distinct-value-39")), p(S("repeat"), S("distinct-value-00")))), "ef436b30305164697374696e63742d76616c75652d3030436b30315164697374696e63742d76616c75652d3031436b30325164697374696e63742d76616c75652d3032436b30335164697374696e63742d76616c75652d3033436b30345164697374696e63742d76616c75652d3034436b30355164697374696e63742d76616c75652d3035436b30365164697374696e63742d76616c75652d3036436b30375164697374696e63742d76616c75652d3037436b30385164697374696e63742d76616c75652d3038436b30395164697374696e63742d76616c75652d3039436b31305164697374696e63742d76616c75652d3130436b31315164697374696e63742d76616c75652d3131436b31325164697374696e63742d76616c75652d3132436b31335164697374696e63742d76616c75652d3133436b31345164697374696e63742d76616c75652d3134436b31355164697374696e63742d76616c75652d3135436b31365164697374696e63742d76616c75652d3136436b31375164697374696e63742d76616c75652d3137436b31385164697374696e63742d76616c75652d3138436b31395164697374696e63742d76616c75652d3139436b32305164697374696e63742d76616c75652d3230436b32315164697374696e63742d76616c75652d3231436b32325164697374696e63742d76616c75652d3232436b32335164697374696e63742d76616c75652d3233436b32345164697374696e63742d76616c75652d3234436b32355164697374696e63742d76616c75652d3235436b32365164697374696e63742d76616c75652d3236436b32375164697374696e63742d76616c75652d3237436b32385164697374696e63742d76616c75652d3238436b32395164697374696e63742d76616c75652d3239436b33305164697374696e63742d76616c75652d3330436b33315164697374696e63742d76616c75652d3331436b33325164697374696e63742d76616c75652d3332436b33335164697374696e63742d76616c75652d3333436b33345164697374696e63742d76616c75652d3334436b33355164697374696e63742d76616c75652d3335436b33365164697374696e63742d76616c75652d3336436b33375164697374696e63742d76616c75652d3337436b33385164697374696e63742d76616c75652d3338436b33395164697374696e63742d76616c75652d333946726570656174a103")

    // ---- SRP (Apple Pair-Setup variant) ----
    @Test fun srpMatchesSrptools() {
        val server = SrpServer("1111", h("00112233445566778899aabbccddeeff"), ByteArray(32) { 0xaa.toByte() })
        assertEquals("60436d941aaa598a0f600edc71bb2d8a2e6336d5cd931a13d30c99c3167e863b09b26750f433c5ef5865f57d63b3d2faaadd9ddaf0514f91833db5740f1981f07439b3b8d253ff8ad318f897803cf9a6e362d509be9b11dd76926a947f5c9263be1b9706e50102e527af8cb29deb24a69f355e62229b6e09c41a31e116c780011817ffb3b44b8ab5b895a73887676f7636d2aa85b8ca6f9fa33622678ef752498857b7fa5fd9f57307ce3cc637fcc3d4f45d71524955d71901344123f9099495cb3ec1df716d60230a06649f0e33a5dd8b81207630d498fb7f21663b53f762c2f0ba8d09ea8e7be09aa487843c47e30e87e6b7ecc47d667ed4b71ab470f95ee9596f34f655eead21d54ae77f022557ba12fb5e688b40b9fc83384be8bdbbff6462a8982cb6b3f644b36b38fd52f99084e53e3d3014c15286b377e47fa4f637cc781d0339a2a7df9130a6b71e162816a10d3ef539f06dc54d41f50de56bc91c69f52e7112547e4f9709c693820f25365f55f1b429d60167a87e7e4607ae0849cd", hex(server.publicKey()))
        server.process(h("bc0e7cf5dc3babf67dcedbb3b140aacc6cac43f4336b43bbd5de48d6ea7c8eda66924e354255225bccad9debe21182e6bb050f3ff3e6cfbb62c229379968c70ca436ad649a0b051373184215eef046f6f1f2256838f958581f6c7b2b85fa4afe326a0e8a951d4489305331aff88a136fd8d108bcc95fceb7e557c889c828bd23fb0702f053e1ca6470fb3c76bce4843fc005c7ea675740f8550212656cfc8919d9db805a434a68229e0d9dfe43fc16dc680a5ce74b77cf374353b05759bc1da3a9dabde30a4209381c87ca83d9483abdf66b86f9b1cbda9ad82c62712b87ce6fb7069b8fc8df344261821a06d0dc5106af76d4245f3f7737a94dbc484b415555dc401842d3011204553ba9f611b02bc38de26eba1a76bf8350205a62c436ba1c3c7c69d59318bd107fd1c1f5d846b3142e85a5d49e522655e020ed1bfe1e186cf923bf328f0b9b4c6a8aa3266ed9125bb98d63827110713be7803122ee4603c54ea31863ce4b10aff31f9073cf63b94733b4f066e72d4ec35687047d5d0db160"))
        assertTrue(server.verifyProof(h("609fc3b240f3b8e8932fc26b1ce3a9a901a1c6024239e5618d086a46c8709d8b635524d90c41f6c465626128242151ca32f57f5a6a14dea074e4cbe08832bd03")), "client proof")
        assertFalse(server.verifyProof(ByteArray(64)), "wrong proof must fail")
        assertEquals("be9f8aa7f23a0826316277b2dc767d1dae1490c28e4f6c9859347ea67331f8ee1cd445ebc982a9f773c8bc2c3dbfc73271ec4e6245d8e3a7e5104503fff27e57", hex(server.keyProofHash()))
        assertEquals("43cfd784738cbb616f7d1b921b1cfd7df0b0f6b6377c89aad7ac070d86b8b3663729cf32db89c33fa7500fd9977ed262becbaeb29f90580777acb4e622f0a85a", hex(server.sessionKey()))
    }

    @Test fun srpKeepsLeadingZeroPins() {
        // "0123" must hash as typed; a numeric PIN would silently become "123" and never match the iPhone.
        val salt = ByteArray(16)
        assertNotEquals(hex(SrpServer("0123", salt, ByteArray(32) { 1 }).publicKey()), hex(SrpServer("123", salt, ByteArray(32) { 1 }).publicKey()))
    }

    // ---- ChaCha20-Poly1305 + HKDF ----
    @Test fun cipherCounterNonceEncrypt() {
        val c = CompanionCipher(h("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"), h("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"), 12)
        assertEquals(hex("639a1d588fdc848e7b0838228d3ef0d3a815a78969c8f73f1ab9b3078947"), hex(c.encrypt(h("7b225f69223a225f68696443227d"), h("0800001e"))))
        assertEquals(hex("777d248781bcf443131891ac19359f04425a51d168a6b17fe113fe14f0c5806fa93c695e074d466e0af007"), hex(c.encrypt(h("e3425f69455f68696443425f740a425f63e2a109455f6842745309"), h("0800002b"))))
        assertEquals(hex("8feae02842e728362c58729271d52803dfef493a3f654da549020b052395bfa2ffbebd50df7ebed12229cfc03d570be13511ed612eaba962a2c0bcbc585cff39d3dce7bfdfd5bf6351dd39d5e6dd844fa9b403558e6a34ed86b8c945122af8357d30cefb0d71f305ac2f1899655c57766293c0a2"), hex(c.encrypt(h("00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"), h("08000074"))))
    }

    @Test fun cipherCounterNonceDecrypt() {
        val c = CompanionCipher(h("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"), h("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"), 12)
        assertEquals("7b225f69223a225f68696443227d", hex(c.decrypt(h("fb4100b531b2f542f2419da2c7f478456b4ec04fba8bdb3419744e6389e0"), h("0800001e"))))
        assertEquals("e3425f69455f68696443425f740a425f63e2a109455f6842745309", hex(c.decrypt(h("26ec4e5547aae3f18fd5bef9b8459b5dca6a46bd82548deb9786f0d1b2d940781df26f6ad9e8f970ee3a26"), h("0800002b"))))
        assertEquals("00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000", hex(c.decrypt(h("93a73cb588876c4628b0fafff0f8def58b38708abbe832c9d8faf236ba45f6492715a57f0e2456d49376caf6efa795c45c52de2f94cb39bd5f8a54347a9c1eb86d866d0998159961c06e75f712cf2c8eed94307410066da5def385d396c21727f4dd57258100a81b31c7a45a770c4fef45e91dbb"), h("08000074"))))
    }

    @Test fun cipherExplicitNonce() {
        val k = h("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
        val c = CompanionCipher(k, k, 8)
        assertEquals("198f7ba9f7542940bae690be2ab48d4fcba60ebded08c2ada6fadd22b21bb728ffcf12fcfabcacca79eaebde164ade58e30dac75b4026063287b26db6e", hex(c.encrypt(h("0109636c69656e742d69640320000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"), null, "PS-Msg05".toByteArray())))
        assertEquals("0109636c69656e742d69640320000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", hex(c.decrypt(h("198f7ba9f7542940bae690be2ab48d4fcba60ebded08c2ada6fadd22b21bb728ffcf12fcfabcacca79eaebde164ade58e30dac75b4026063287b26db6e"), null, "PS-Msg05".toByteArray())))
    }

    @Test fun hkdfMatches() {
        val shared = h("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
        assertEquals("8f806335e1847b59c5004b131c4689e951ce9808993aed540856475e0dee353b", hex(AtvCrypto.hkdf("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)))
        assertEquals("43020386f29e4f8016eadaf9233973760ec466e2af1668eb85f31e4d90231649", hex(AtvCrypto.hkdf("", "ServerEncrypt-main", shared)))
    }

    // ---- TLV8 + gestures ----
    @Test fun tlvSplitsAndJoinsLongValues() {
        val big = ByteArray(600) { it.toByte() }
        val packed = Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(1), Tlv8.PUBLIC_KEY to big)
        assertEquals(3 + 2 + 255 + 2 + 255 + 2 + 90, packed.size)
        val back = Tlv8.read(packed)
        assertContentEquals(big, back[Tlv8.PUBLIC_KEY])
        assertContentEquals(byteArrayOf(1), back[Tlv8.SEQ_NO])
    }

    @Test fun strokesBecomeGestures() {
        assertEquals(IosGesture.TAP, IosGesture.fromStroke(10.0, -20.0))
        assertNull(IosGesture.fromStroke(100.0, 0.0))
        assertEquals(IosGesture.SWIPE_UP, IosGesture.fromStroke(20.0, -400.0))
        assertEquals(IosGesture.SWIPE_DOWN, IosGesture.fromStroke(-20.0, 400.0))
        assertEquals(IosGesture.SWIPE_LEFT, IosGesture.fromStroke(-300.0, 50.0))
        assertEquals(IosGesture.SWIPE_RIGHT, IosGesture.fromStroke(300.0, 50.0))
        assertEquals("ios_up", IosGesture.fromHid(1))
        assertEquals("ios_power", IosGesture.fromHid(19))
        assertEquals("ios_button_42", IosGesture.fromHid(42))
        assertTrue(IosGesture.LABELS.keys.all { it.startsWith(IosGesture.PREFIX) })
    }
}
