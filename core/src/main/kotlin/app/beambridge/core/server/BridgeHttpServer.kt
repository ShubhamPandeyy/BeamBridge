package app.beambridge.core.server

import app.beambridge.core.data.*
import app.beambridge.core.ir.IrCode
import app.beambridge.core.ir.IrEncodingException
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.*
import java.net.URLDecoder
import java.util.Base64

/**
 * Embedded LAN HTTP server: the web dashboard plus a JSON API covering everything the app can do.
 * Parameters may come from the query string, a form body, or a JSON body. See README.md for the endpoint list.
 */
class BridgeHttpServer(
    hostname: String?,
    port: Int,
    private val bridge: Bridge,
) : NanoHTTPD(hostname, port) {

    private val controller get() = bridge.controller
    private val endpoints get() = bridge.endpoints
    private val json = Json { prettyPrint = false }

    fun startServer() = start(SOCKET_READ_TIMEOUT, true)

    override fun serve(session: IHTTPSession): Response = try {
        when {
            session.method == Method.OPTIONS -> cors(newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, ""))
            !authorized(session) -> cors(newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", """{"status":"error","error":"Login required"}""")
                .apply { addHeader("WWW-Authenticate", "Basic realm=\"BeamBridge\", charset=\"UTF-8\"") })
            else -> cors(route(session))
        }
    } catch (e: Exception) {
        cors(error(Response.Status.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName))
    }

    private fun authorized(s: IHTTPSession): Boolean {
        val cfg = bridge.settings.settings.value
        if (cfg.authMode == AuthMode.GUEST) return true
        val header = s.headers["authorization"] ?: return false
        if (!header.startsWith("Basic ", true)) return false
        val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(6).trim()), Charsets.UTF_8) }.getOrNull() ?: return false
        val user = decoded.substringBefore(':')
        val pass = decoded.substringAfter(':', "")
        return constantTimeEquals(user, cfg.username) and constantTimeEquals(pass, cfg.password)
    }

    private fun constantTimeEquals(a: String, b: String) =
        java.security.MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun route(s: IHTTPSession): Response {
        val path = s.uri.trimEnd('/').ifEmpty { "/" }
        val parts = path.removePrefix("/").split('/').map { URLDecoder.decode(it, "UTF-8") }
        val m = s.method
        return when {
            path == "/" || path == "/index.html" -> webUi()
            path == "/api/status" -> ok(status())
            path == "/api/blast" -> only(m, Method.GET, Method.POST) ?: blast(params(s))
            path == "/api/raw" -> only(m, Method.POST, Method.GET) ?: raw(params(s))
            path == "/api/ac" -> only(m, Method.GET, Method.POST) ?: ac(params(s))

            // devices
            path == "/api/devices" && m == Method.GET -> ok(JsonArray(bridge.store.devices.value.map(::deviceJson)))
            path == "/api/devices" && m == Method.POST -> createDevice(params(s))
            parts.size == 3 && parts[1] == "devices" -> device(m, parts[2], params(s))
            parts.size >= 4 && parts[1] == "devices" && parts[3] == "buttons" -> buttons(m, parts[2], parts.getOrNull(4), params(s))

            // catalog / pairing
            path == "/api/catalog/brands" -> brands(params(s))
            path == "/api/catalog/candidates" -> candidates(params(s))
            path == "/api/catalog/test" -> only(m, Method.POST) ?: catalogTest(params(s))

            // finder
            path == "/api/finder" -> ok(finderJson())
            path == "/api/finder/keys" -> ok(JsonArray(StandardKeys.FINDER_KEYS.map(::JsonPrimitive)))
            parts.size == 3 && parts[1] == "finder" -> only(m, Method.POST) ?: finder(parts[2], params(s))

            // settings
            path == "/api/settings" && m == Method.GET -> ok(settingsJson())
            path == "/api/settings" && m == Method.POST -> saveSettings(params(s))
            path == "/api/network" -> ok(networkJson())
            path == "/api/network/check" -> checkNetwork(params(s))

            else -> error(Response.Status.NOT_FOUND, "No route for ${m.name} ${s.uri}")
        }
    }

    // ---------------------------------------------------------------- IR

    private fun status() = buildJsonObject {
        put("status", "ok")
        put("app", "BeamBridge")
        put("ir_emitter", controller.emitterAvailable)
        put("base_url", endpoints.base())
        put("devices", bridge.store.devices.value.size)
        put("auth", bridge.settings.settings.value.authMode.name.lowercase())
    }

    private fun blast(p: Map<String, String>): Response {
        val device = p["device"] ?: return error(Response.Status.BAD_REQUEST, "Missing 'device'")
        val button = p["button"] ?: return error(Response.Status.BAD_REQUEST, "Missing 'button'")
        return result(controller.blast(device, button)) { put("device", device); put("button", button) }
    }

    private fun parseCode(p: Map<String, String>): Pair<IrCode?, String?> = try {
        CodeInput.parse(p) to null
    } catch (e: IrEncodingException) {
        null to (e.message ?: "Bad code")
    } catch (e: NumberFormatException) {
        null to "Bad number"
    }

    private fun raw(p: Map<String, String>): Response {
        if (p["pattern"] != null && p["frequency"]?.toIntOrNull() == null && p["ir"] == null) return error(Response.Status.BAD_REQUEST, "Missing or bad 'frequency'")
        val (code, err) = parseCode(p)
        err?.let { return error(Response.Status.BAD_REQUEST, it) }
        code ?: return error(Response.Status.BAD_REQUEST, "Provide 'ir', 'frequency'+'pattern', 'pronto', or 'protocol'+'code'")
        return result(controller.send(code, toggleKey = "raw"))
    }

    private fun ac(p: Map<String, String>): Response {
        val device = p["device"] ?: return error(Response.Status.BAD_REQUEST, "Missing 'device'")
        val (res, state) = controller.ac(device) { cur ->
            AcState(
                power = p["power"]?.let { it.equals("on", true) || it == "1" || it.equals("true", true) } ?: cur.power,
                mode = p["mode"]?.lowercase() ?: cur.mode,
                temperature = (p["temp"] ?: p["temperature"])?.toIntOrNull() ?: cur.temperature,
                fan = p["fan"]?.lowercase() ?: cur.fan,
            )
        }
        return result(res) {
            put("device", device)
            state?.let { put("state", acJson(it)) }
        }
    }

    // ---------------------------------------------------------------- devices

    private fun createDevice(p: Map<String, String>): Response {
        val type = p["type"]?.let(::deviceType) ?: return error(Response.Status.BAD_REQUEST, "Missing or bad 'type'")
        val name = p["name"].orEmpty()
        if (type == DeviceType.OTHER || p["brand"] == null) {
            val first = p["button"]?.takeIf { it.isNotBlank() }?.let { b ->
                val (code, err) = parseCode(p)
                err?.let { return error(Response.Status.BAD_REQUEST, it) }
                code?.let { SavedButton(buttonName(b), it, custom = true) }
            }
            return ok(deviceJson(bridge.pairing.createCustom(name, type, first)), Response.Status.CREATED)
        }
        val brand = p["brand"]?.toIntOrNull() ?: return error(Response.Status.BAD_REQUEST, "Bad 'brand'")
        val index = p["index"]?.toIntOrNull() ?: 0
        val c = bridge.pairing.candidates(type, brand).getOrNull(index) ?: return error(Response.Status.NOT_FOUND, "No candidate #$index")
        val brandName = p["brand_name"] ?: bridge.pairing.brandName(type, brand)
        return ok(deviceJson(bridge.pairing.create(type, c, name, brandName)), Response.Status.CREATED)
    }

    private fun device(m: Method, id: String, p: Map<String, String>): Response {
        val d = bridge.store.get(id) ?: return error(Response.Status.NOT_FOUND, "Unknown device '$id'")
        return when (m) {
            Method.GET -> ok(deviceJson(d))
            Method.DELETE -> { bridge.store.remove(d.id); ok(buildJsonObject { put("status", "ok") }) }
            Method.POST, Method.PUT -> {
                p["name"]?.takeIf { it.isNotBlank() }?.let { n -> bridge.store.update(d.id) { it.copy(name = n.trim()) } }
                p["archived"]?.let { bridge.setArchived(d.id, it.toBool()) }
                ok(deviceJson(bridge.store.get(d.id)!!))
            }
            else -> error(Response.Status.METHOD_NOT_ALLOWED, "Use GET/POST/DELETE")
        }
    }

    private fun buttons(m: Method, id: String, button: String?, p: Map<String, String>): Response {
        val d = bridge.store.get(id) ?: return error(Response.Status.NOT_FOUND, "Unknown device '$id'")
        return when {
            m == Method.DELETE && button != null -> { bridge.store.removeButton(d.id, button); ok(deviceJson(bridge.store.get(d.id)!!)) }
            m == Method.POST && button != null && p["hidden"] != null -> { bridge.setHidden(d.id, button, p.getValue("hidden").toBool()); ok(deviceJson(bridge.store.get(d.id)!!)) }
            m == Method.POST && button != null && p["cell"] != null -> {
                bridge.moveButton(d.id, button, p["cell"]?.toIntOrNull() ?: return error(Response.Status.BAD_REQUEST, "Bad 'cell'"))
                ok(deviceJson(bridge.store.get(d.id)!!))
            }
            m == Method.POST -> {
                val name = buttonName(p["name"] ?: button ?: return error(Response.Status.BAD_REQUEST, "Missing 'name'"))
                if (name.isEmpty()) return error(Response.Status.BAD_REQUEST, "Missing 'name'")
                val (code, err) = parseCode(p)
                err?.let { return error(Response.Status.BAD_REQUEST, it) }
                code ?: return error(Response.Status.BAD_REQUEST, "Missing IR code. Examples: ${CodeInput.EXAMPLES}")
                bridge.store.upsertButton(d.id, SavedButton(name, code, custom = true), p["replacing"] ?: button)
                ok(deviceJson(bridge.store.get(d.id)!!), Response.Status.CREATED)
            }
            else -> error(Response.Status.METHOD_NOT_ALLOWED, "Use POST/DELETE")
        }
    }

    // ---------------------------------------------------------------- pairing

    private fun brands(p: Map<String, String>): Response {
        val type = p["type"]?.let(::deviceType) ?: return error(Response.Status.BAD_REQUEST, "Missing 'type'")
        return ok(JsonArray(bridge.pairing.brands(type, p["q"].orEmpty()).map { b ->
            buildJsonObject { put("id", b.id); put("name", b.name); put("remotes", b.remotes) }
        }))
    }

    private fun candidates(p: Map<String, String>): Response {
        val type = p["type"]?.let(::deviceType) ?: return error(Response.Status.BAD_REQUEST, "Missing 'type'")
        val brand = p["brand"]?.toIntOrNull() ?: return error(Response.Status.BAD_REQUEST, "Missing 'brand'")
        val on = p["on"]?.toBool() ?: false
        return ok(JsonArray(bridge.pairing.candidates(type, brand).mapIndexed { i, c ->
            buildJsonObject { put("index", i); put("label", c.label); put("test_key", bridge.pairing.testKey(c, on)) }
        }))
    }

    private fun catalogTest(p: Map<String, String>): Response {
        val type = p["type"]?.let(::deviceType) ?: return error(Response.Status.BAD_REQUEST, "Missing 'type'")
        val brand = p["brand"]?.toIntOrNull() ?: return error(Response.Status.BAD_REQUEST, "Missing 'brand'")
        val index = p["index"]?.toIntOrNull() ?: 0
        val c = bridge.pairing.candidates(type, brand).getOrNull(index) ?: return error(Response.Status.NOT_FOUND, "No candidate #$index")
        val on = p["on"]?.toBool() ?: false
        return result(bridge.pairing.test(c, on)) { put("test_key", bridge.pairing.testKey(c, on)) }
    }

    // ---------------------------------------------------------------- finder

    private fun finder(action: String, p: Map<String, String>): Response {
        val f = bridge.finder
        when (action) {
            "start" -> f.start(p["key"]?.uppercase() ?: return error(Response.Status.BAD_REQUEST, "Missing 'key'"), p["interval"]?.toLongOrNull()?.coerceIn(300, 5000) ?: 900)
            "stop" -> f.stop()
            "resume" -> f.resume()
            "page" -> f.setPage(p["page"]?.toIntOrNull() ?: 0)
            "test" -> return result(f.test(p["index"]?.toIntOrNull() ?: return error(Response.Status.BAD_REQUEST, "Missing 'index'")))
            "accept" -> {
                val device = p["device"] ?: return error(Response.Status.BAD_REQUEST, "Missing 'device'")
                val index = p["index"]?.toIntOrNull() ?: return error(Response.Status.BAD_REQUEST, "Missing 'index'")
                val d = f.accept(device, p["name"] ?: f.state.value.key.orEmpty(), index) ?: return error(Response.Status.NOT_FOUND, "Unknown device or code")
                return ok(deviceJson(d))
            }
            "reset" -> f.reset()
            else -> return error(Response.Status.NOT_FOUND, "Unknown finder action '$action'")
        }
        return ok(finderJson())
    }

    private fun finderJson() = buildJsonObject {
        val s = bridge.finder.state.value
        put("key", s.key)
        put("running", s.running)
        put("sent", s.sent)
        put("total", s.total)
        put("page", s.page)
        put("done", s.done)
        putJsonArray("window") {
            if (!s.running) bridge.finder.window().forEach { (i, c) -> addJsonObject { put("index", i); put("label", c.label) } }
        }
    }

    // ---------------------------------------------------------------- settings

    private fun settingsJson() = buildJsonObject {
        val c = bridge.settings.settings.value
        put("port", c.port)
        put("bind_address", c.bindAddress)
        put("auth", c.authMode.name.lowercase())
        put("username", c.username)
        put("has_password", c.password.isNotEmpty())
        put("wake_lock", c.wakeLock)
        put("auto_start", c.autoStart)
    }

    private fun saveSettings(p: Map<String, String>): Response {
        val cur = bridge.settings.settings.value
        val next = cur.copy(
            port = p["port"]?.toIntOrNull() ?: cur.port,
            bindAddress = p["bind_address"] ?: cur.bindAddress,
            authMode = p["auth"]?.let { if (it.equals("password", true)) AuthMode.PASSWORD else AuthMode.GUEST } ?: cur.authMode,
            username = p["username"] ?: cur.username,
            password = p["password"]?.takeIf { it.isNotEmpty() } ?: cur.password,
            wakeLock = p["wake_lock"]?.toBool() ?: cur.wakeLock,
            autoStart = p["auto_start"]?.toBool() ?: cur.autoStart,
        )
        bridge.applySettings(next)?.let { return error(Response.Status.BAD_REQUEST, it) }
        return ok(settingsJson())
    }

    private fun networkJson() = buildJsonObject {
        putJsonArray("options") { Net.bindOptions().forEach { o -> addJsonObject { put("address", o.address); put("label", o.label) } } }
        put("current", bridge.settings.settings.value.bindAddress)
    }

    private fun checkNetwork(p: Map<String, String>): Response {
        val address = p["address"] ?: "auto"
        val port = p["port"]?.toIntOrNull() ?: return error(Response.Status.BAD_REQUEST, "Missing 'port'")
        val host = Net.resolve(address)
        val present = host != ""
        val free = present && bridge.isFree(host, port)
        return ok(buildJsonObject {
            put("status", "ok"); put("present", present); put("available", free)
            put("url", "http://${Net.publicHost(host?.takeIf { it.isNotEmpty() }) ?: "<phone-ip>"}:$port")
        })
    }

    // ---------------------------------------------------------------- JSON

    private fun deviceJson(d: SavedDevice) = buildJsonObject {
        put("id", d.id)
        put("name", d.name)
        put("type", d.type.name.lowercase())
        d.brand?.let { put("brand", it) }
        put("archived", d.archived)
        put("endpoint", endpoints.device(d.id))
        val custom = d.buttons.filter { it.custom }.associateBy { it.name }
        putJsonArray("buttons") {
            controller.buttonNames(d).forEach { name ->
                addJsonObject {
                    put("name", name)
                    put("custom", name in custom)
                    put("hidden", d.hidden.any { it.equals(name, true) })
                    put("url", endpoints.blast(d.id, name))
                    custom[name]?.let { put("code", codeText(it.code)) }
                }
            }
        }
        val l = bridge.layout(d)
        putJsonObject("layout") {
            listOf("mute" to l.mute, "power" to l.power, "up" to l.up, "down" to l.down, "left" to l.left, "right" to l.right,
                "ok" to l.ok, "vol_down" to l.volDown, "vol_up" to l.volUp, "ch_down" to l.chDown, "ch_up" to l.chUp,
                "home" to l.home, "back" to l.back).forEach { (k, v) -> put(k, v) }
            putJsonArray("extras") { l.extras.forEach { add(it) } }
        }
        d.acState?.let { put("ac_state", acJson(it)); put("ac_url", endpoints.ac(d.id, it)) }
        controller.acRemote(d)?.let { r ->
            putJsonObject("ac_caps") {
                putJsonArray("modes") { r.modes.forEach { add(it) } }
                putJsonArray("fans") { r.fanSpeeds.forEach { add(it) } }
                put("min", r.temperatures.first); put("max", r.temperatures.last)
            }
        }
    }

    private fun codeText(c: IrCode) = when (c) {
        is IrCode.Hex -> "${c.protocol} ${c.code}${if (c.bits > 0) " ${c.bits}" else ""}"
        is IrCode.Raw -> c.pattern.joinToString(",")
        is IrCode.Pronto -> c.pronto
    }

    private fun acJson(s: AcState) = buildJsonObject {
        put("power", s.power); put("mode", s.mode); put("temp", s.temperature); put("fan", s.fan)
    }

    private fun result(r: SendResult, extra: JsonObjectBuilder.() -> Unit = {}): Response = when (r) {
        is SendResult.Ok -> ok(buildJsonObject {
            put("status", "ok")
            extra()
            put("frequency", r.signal.frequency)
            put("pulses", r.signal.pattern.size)
        })
        is SendResult.NotFound -> error(Response.Status.NOT_FOUND, r.message)
        is SendResult.Failed -> error(Response.Status.BAD_REQUEST, r.message)
    }

    // ---------------------------------------------------------------- plumbing

    private fun deviceType(s: String): DeviceType? = when (s.lowercase()) {
        "tv" -> DeviceType.TV
        "ac" -> DeviceType.AC
        "stb", "set_top_box", "settopbox" -> DeviceType.SET_TOP_BOX
        "other", "custom" -> DeviceType.OTHER
        else -> null
    }

    private fun buttonName(s: String) = s.trim().uppercase().replace(Regex("\\s+"), "_")
    private fun String.toBool() = equals("true", true) || this == "1" || equals("on", true) || equals("yes", true)

    private fun params(s: IHTTPSession): Map<String, String> {
        val out = linkedMapOf<String, String>()
        if (s.method == Method.POST || s.method == Method.PUT || s.method == Method.DELETE) {
            val files = HashMap<String, String>()
            runCatching { s.parseBody(files) }
            files["postData"]?.let { body ->
                runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()?.forEach { (k, v) ->
                    out[k] = when (v) {
                        is JsonArray -> v.joinToString(",") { (it as? JsonPrimitive)?.content ?: it.toString() }
                        is JsonPrimitive -> v.content
                        else -> v.toString()
                    }
                }
            }
        }
        s.parameters.forEach { (k, v) -> if (k !in out && v.isNotEmpty()) out[k] = v.first() }
        return out
    }

    private fun only(m: Method, vararg allowed: Method): Response? =
        if (m in allowed) null else error(Response.Status.METHOD_NOT_ALLOWED, "Use ${allowed.joinToString("/")}")

    private fun ok(body: JsonElement, status: Response.Status = Response.Status.OK) =
        newFixedLengthResponse(status, "application/json", json.encodeToString(JsonElement.serializer(), body))

    private fun error(status: Response.IStatus, message: String) = newFixedLengthResponse(
        status, "application/json",
        json.encodeToString(JsonElement.serializer(), buildJsonObject { put("status", "error"); put("error", message) }),
    )

    private fun cors(r: Response) = r.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        addHeader("Cache-Control", "no-store")
    }

    private fun webUi(): Response {
        val html = javaClass.getResourceAsStream("/web/index.html")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return error(Response.Status.NOT_FOUND, "Web UI missing")
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }
}
