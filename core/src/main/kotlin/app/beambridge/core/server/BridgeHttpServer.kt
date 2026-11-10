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
            // A web page on another site must never drive the bridge through the user's browser (CSRF).
            crossSite(session) && changesState(session) -> cors(error(Response.Status.FORBIDDEN, "Cross-site request blocked"))
            !authorized(session) -> cors(newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", """{"status":"error","error":"Login required"}""")
                .apply { addHeader("WWW-Authenticate", "Basic realm=\"BeamBridge\", charset=\"UTF-8\"") })
            !bridge.isReady() && session.uri.startsWith("/api/") && session.uri.trimEnd('/') != "/api/status" ->
                cors(error(Response.Status.SERVICE_UNAVAILABLE, "Still loading the code database, try again in a moment").apply { addHeader("Retry-After", "2") })
            session.method == Method.GET && isAction(session.uri) && !bridge.settings.settings.value.allowGetActions ->
                cors(error(Response.Status.METHOD_NOT_ALLOWED, "GET actions are turned off in Settings. Use POST"))
            else -> cors(route(session))
        }
    } catch (e: Exception) {
        cors(error(Response.Status.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName))
    }

    /** Last header that passed, keyed to the hash it was checked against (PBKDF2 is too slow to run per request). */
    @Volatile private var verified: Pair<String, String>? = null

    private fun authorized(s: IHTTPSession): Boolean {
        val cfg = bridge.settings.settings.value
        if (cfg.authMode == AuthMode.GUEST) return true
        val header = s.headers["authorization"] ?: return false
        if (!header.startsWith("Basic ", true)) return false
        verified?.let { (h, hash) -> if (hash == cfg.passwordHash && constantTimeEquals(h, header)) return true }
        val decoded = runCatching { String(Base64.getDecoder().decode(header.substring(6).trim()), Charsets.UTF_8) }.getOrNull() ?: return false
        val user = decoded.substringBefore(':')
        val pass = decoded.substringAfter(':', "")
        val ok = constantTimeEquals(user, cfg.username) and cfg.checkPassword(pass)
        if (ok) verified = header to cfg.passwordHash
        return ok
    }

    /** Browsers mark requests a page from another site makes; scripts, curl and Home Assistant send neither header. */
    private fun crossSite(s: IHTTPSession): Boolean {
        val site = s.headers["sec-fetch-site"]?.lowercase()
        if (site == "cross-site" || site == "same-site") return true
        val origin = s.headers["origin"] ?: return false
        val host = s.headers["host"] ?: return true
        return runCatching { java.net.URI(origin).authority }.getOrNull()?.equals(host, true) != true
    }

    private fun changesState(s: IHTTPSession) = s.method != Method.GET || isAction(s.uri)

    /** Endpoints that transmit IR or drive the finder, even over GET. */
    private fun isAction(uri: String): Boolean {
        val u = uri.trimEnd('/')
        return u == "/api/blast" || u == "/api/raw" || u == "/api/ac" || u == "/api/scene" ||
            (u.startsWith("/api/scenes/") && u.endsWith("/run")) || (u.startsWith("/api/finder/") && u != "/api/finder/keys")
    }

    private fun constantTimeEquals(a: String, b: String) =
        java.security.MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** Who sent the current request: the bundled web UI tags itself, everything else is "api". */
    private val source = ThreadLocal.withInitial { "api" }

    private fun route(s: IHTTPSession): Response {
        source.set(if (s.headers["x-beambridge-client"] == "web") "web" else "api")
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
            parts.size == 5 && parts[1] == "devices" && parts[3] == "tiles" && parts[4] == "reset" -> only(m, Method.POST) ?: run {
                bridge.store.get(parts[2]) ?: return error(Response.Status.NOT_FOUND, "Unknown device '${parts[2]}'")
                bridge.resetGrid(parts[2]); ok(deviceJson(bridge.store.get(parts[2])!!))
            }
            parts.size == 5 && parts[1] == "devices" && parts[3] == "tiles" -> only(m, Method.POST) ?: params(s).let { p ->
                fun n(k: String) = p[k]?.toIntOrNull()
                val col = n("col"); val row = n("row"); val w = n("w") ?: 1; val h = n("h") ?: 1
                if (col == null || row == null) return error(Response.Status.BAD_REQUEST, "Need integer col and row (and optional w, h)")
                bridge.moveTile(parts[2], parts[4], col, row, w, h)?.let { return error(if (it.startsWith("Unknown")) Response.Status.NOT_FOUND else Response.Status.BAD_REQUEST, it) }
                ok(deviceJson(bridge.store.get(parts[2])!!))
            }
            parts.size >= 4 && parts[1] == "devices" && parts[3] == "buttons" -> buttons(m, parts[2], parts.getOrNull(4), params(s))

            // catalog / pairing
            path == "/api/catalog/brands" -> brands(params(s))
            path == "/api/catalog/candidates" -> candidates(params(s))
            path == "/api/catalog/test" -> only(m, Method.POST) ?: catalogTest(params(s))

            // finder
            path == "/api/finder" -> ok(finderJson())
            path == "/api/finder/keys" -> ok(JsonArray(StandardKeys.FINDER_KEYS.map(::JsonPrimitive)))
            parts.size == 3 && parts[1] == "finder" -> only(m, Method.POST) ?: finder(parts[2], params(s))

            // history
            path == "/api/history" && m == Method.GET -> history(params(s))
            path == "/api/history" && m == Method.DELETE -> { bridge.history.clear(); ok(buildJsonObject { put("status", "ok") }) }

            // scenes
            path == "/api/scenes" && m == Method.GET -> ok(JsonArray(bridge.scenes.scenes.value.map(::sceneJson)))
            path == "/api/scenes" && m == Method.POST -> saveScene(null, s)
            path == "/api/scene" -> only(m, Method.GET, Method.POST) ?: params(s).let { runScene(it["name"] ?: it["id"], it["wait"]) }
            parts.size == 3 && parts[1] == "scenes" -> when (m) {
                Method.GET -> bridge.scenes.get(parts[2])?.let { ok(sceneJson(it)) } ?: error(Response.Status.NOT_FOUND, "Unknown scene '${parts[2]}'")
                Method.POST, Method.PUT -> saveScene(parts[2], s)
                Method.DELETE -> if (bridge.scenes.remove(parts[2])) ok(buildJsonObject { put("status", "ok") }) else error(Response.Status.NOT_FOUND, "Unknown scene '${parts[2]}'")
                else -> error(Response.Status.METHOD_NOT_ALLOWED, "Use GET/POST/DELETE")
            }
            parts.size == 4 && parts[1] == "scenes" && parts[3] == "run" -> only(m, Method.POST, Method.GET) ?: runScene(parts[2], params(s)["wait"])

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
        put("ready", bridge.isReady())
        put("base_url", endpoints.base())
        put("devices", bridge.store.devices.value.size)
        put("auth", bridge.settings.settings.value.authMode.name.lowercase())
    }

    private fun blast(p: Map<String, String>): Response {
        val device = p["device"] ?: return error(Response.Status.BAD_REQUEST, "Missing 'device'")
        val button = p["button"] ?: return error(Response.Status.BAD_REQUEST, "Missing 'button'")
        return result(controller.blast(device, button, source.get())) { put("device", device); put("button", button) }
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
        return result(controller.send(code, toggleKey = "raw", label = "RAW ${CodeInput.format(code).take(40)}", source = source.get()))
    }

    private fun ac(p: Map<String, String>): Response {
        val device = p["device"] ?: return error(Response.Status.BAD_REQUEST, "Missing 'device'")
        val power = p["power"]?.let { it.strictBool() ?: return error(Response.Status.BAD_REQUEST, "Invalid power '$it'. Use on or off") }
        val temp = (p["temp"] ?: p["temperature"])?.let { it.trim().toIntOrNull() ?: return error(Response.Status.BAD_REQUEST, "Invalid temp '$it'") }
        val (res, state) = controller.ac(device, source.get()) { cur ->
            AcState(
                power = power ?: cur.power,
                mode = p["mode"]?.lowercase() ?: cur.mode,
                temperature = temp ?: cur.temperature,
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
            Method.DELETE -> { val fixed = bridge.deleteDevice(d.id); ok(buildJsonObject { put("status", "ok"); put("scenes_updated", fixed) }) }
            Method.POST, Method.PUT -> {
                val archived = p["archived"]?.let { it.strictBool() ?: return error(Response.Status.BAD_REQUEST, "Invalid archived '$it'. Use true or false") }
                val swipe = p["swipe"]?.let { it.strictBool() ?: return error(Response.Status.BAD_REQUEST, "Invalid swipe '$it'. Use true or false") }
                p["name"]?.takeIf { it.isNotBlank() }?.let { n -> bridge.store.update(d.id) { it.copy(name = n.trim()) } }
                archived?.let { bridge.setArchived(d.id, it) }
                swipe?.let { bridge.setSwipe(d.id, it) }
                if (p["grid_cols"] != null || p["grid_rows"] != null) {
                    val c = p["grid_cols"]?.let { it.toIntOrNull() ?: return error(Response.Status.BAD_REQUEST, "Invalid grid_cols '$it'") }
                    val r = p["grid_rows"]?.let { it.toIntOrNull() ?: return error(Response.Status.BAD_REQUEST, "Invalid grid_rows '$it'") }
                    bridge.setGridSize(d.id, c, r)?.let { return error(Response.Status.BAD_REQUEST, it) }
                }
                ok(deviceJson(bridge.store.get(d.id)!!))
            }
            else -> error(Response.Status.METHOD_NOT_ALLOWED, "Use GET/POST/DELETE")
        }
    }

    private fun buttons(m: Method, id: String, button: String?, p: Map<String, String>): Response {
        val d = bridge.store.get(id) ?: return error(Response.Status.NOT_FOUND, "Unknown device '$id'")
        return when {
            m == Method.DELETE && button != null ->
                if (bridge.store.removeButton(d.id, button)) ok(deviceJson(bridge.store.get(d.id)!!))
                else error(Response.Status.NOT_FOUND, "Device '${d.id}' has no custom button '$button'")
            m == Method.POST && button != null && (p["hidden"] != null || p["favorite"] != null || p["onscreen"] != null) -> {
                val flags = listOf("hidden", "favorite", "onscreen").associateWith { k -> p[k] }.filterValues { it != null }
                flags.entries.firstOrNull { it.value!!.strictBool() == null }?.let { (k, v) -> return error(Response.Status.BAD_REQUEST, "Invalid $k '$v'. Use true or false") }
                val hidden = flags["hidden"]?.strictBool(); val fav = flags["favorite"]?.strictBool(); val on = flags["onscreen"]?.strictBool()
                hidden?.let { bridge.setHidden(d.id, button, it) }
                fav?.let { bridge.setFavorite(d.id, button, it) }
                on?.let { bridge.setOnScreen(d.id, button, it) }
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
        val on = p["on"]?.let { it.strictBool() ?: return error(Response.Status.BAD_REQUEST, "Invalid on '$it'") } ?: false
        return ok(JsonArray(bridge.pairing.candidates(type, brand).mapIndexed { i, c ->
            buildJsonObject { put("index", i); put("label", c.label); put("test_key", bridge.pairing.testKey(c, on)) }
        }))
    }

    private fun catalogTest(p: Map<String, String>): Response {
        val type = p["type"]?.let(::deviceType) ?: return error(Response.Status.BAD_REQUEST, "Missing 'type'")
        val brand = p["brand"]?.toIntOrNull() ?: return error(Response.Status.BAD_REQUEST, "Missing 'brand'")
        val index = p["index"]?.toIntOrNull() ?: 0
        val c = bridge.pairing.candidates(type, brand).getOrNull(index) ?: return error(Response.Status.NOT_FOUND, "No candidate #$index")
        val on = p["on"]?.let { it.strictBool() ?: return error(Response.Status.BAD_REQUEST, "Invalid on '$it'") } ?: false
        return result(bridge.pairing.test(c, on)) { put("test_key", bridge.pairing.testKey(c, on)) }
    }

    // ---------------------------------------------------------------- finder

    private fun finder(action: String, p: Map<String, String>): Response {
        val f = bridge.finder
        when (action) {
            "start" -> {
                val key = p["key"]?.uppercase() ?: return error(Response.Status.BAD_REQUEST, "Missing 'key'")
                if (f.state.value.running && f.state.value.owner != source.get() && p["force"]?.strictBool() != true)
                    return error(Response.Status.CONFLICT, "The finder is running on the ${f.state.value.owner}. Stop it there, or send force=true")
                f.start(key, p["interval"]?.toLongOrNull()?.coerceIn(300, 5000) ?: 900, owner = source.get())
            }
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
        put("owner", s.owner)
        put("sent", s.sent)
        put("total", s.total)
        put("page", s.page)
        put("done", s.done)
        putJsonArray("window") {
            if (!s.running) bridge.finder.window().forEach { (i, c) -> addJsonObject { put("index", i); put("label", c.label) } }
        }
    }

    // ---------------------------------------------------------------- history & scenes

    private fun history(p: Map<String, String>): Response {
        val limit = p["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..200 } ?: return error(Response.Status.BAD_REQUEST, "limit must be 1-200") } ?: 50
        return ok(JsonArray(bridge.history.entries.value.take(limit).map { e ->
            buildJsonObject {
                put("time", e.time); e.device?.let { put("device", it) }; e.deviceName?.let { put("device_name", it) }
                put("button", e.button); put("source", e.source); put("ok", e.ok); e.message?.let { put("message", it) }
            }
        }))
    }

    private fun sceneJson(sc: Scene) = buildJsonObject {
        put("id", sc.id); put("name", sc.name)
        put("url", "${endpoints.base()}/api/scenes/${java.net.URLEncoder.encode(sc.id, "UTF-8")}/run")
        putJsonArray("steps") { sc.steps.forEach { st -> addJsonObject {
            if (st.isWait) put("wait", st.delayMs) else { put("device", st.device); put("button", st.button) }
        } } }
    }

    /**
     * Body: JSON {"name": "...", "steps": [{"device":"tv","button":"POWER"}, {"wait":1500}, ...]}
     * or form fields name + steps="tv:POWER, wait:1500, ac:POWER_ON".
     */
    private fun saveScene(id: String?, s: IHTTPSession): Response {
        val p = params(s)
        val body = p["__body"]?.takeIf { it.trimStart().startsWith("{") }
        val (name, steps) = try {
            if (body != null) {
                val o = Json.parseToJsonElement(body).jsonObject
                (o["name"]?.jsonPrimitive?.content ?: "") to (o["steps"]?.jsonArray ?: JsonArray(emptyList())).map { el ->
                    val so = el.jsonObject
                    so["wait"]?.jsonPrimitive?.content?.let { SceneStep(delayMs = it.toLongOrNull() ?: throw IllegalArgumentException("Bad wait '$it'")) }
                        ?: SceneStep(so["device"]?.jsonPrimitive?.content ?: "", so["button"]?.jsonPrimitive?.content ?: "")
                }
            } else (p["name"] ?: "") to SceneText.parse(p["steps"] ?: "")
        } catch (e: Exception) {
            return error(Response.Status.BAD_REQUEST, e.message ?: "Bad scene")
        }
        return bridge.saveScene(id, name.ifBlank { id?.let { bridge.scenes.get(it)?.name } ?: "" }, steps).fold(
            { ok(sceneJson(it), Response.Status.CREATED) },
            { error(Response.Status.BAD_REQUEST, it.message ?: "Bad scene") },
        )
    }

    /** Scenes run one at a time. wait=false answers 202 at once (for long scenes); otherwise the response has every step. */
    private fun runScene(idOrName: String?, wait: String? = null): Response {
        idOrName ?: return error(Response.Status.BAD_REQUEST, "Missing 'name'")
        val sc = bridge.scenes.get(idOrName) ?: return error(Response.Status.NOT_FOUND, "Unknown scene '$idOrName'")
        val src = if (source.get() == "web") "web" else "scene"
        val waitFor = wait?.let { it.strictBool() ?: return error(Response.Status.BAD_REQUEST, "Invalid wait '$it'") } ?: true
        if (!waitFor) {
            bridge.runSceneAsync(sc, src)
            return ok(buildJsonObject { put("status", "queued"); put("scene", sc.id) }, Response.Status.ACCEPTED)
        }
        val results = bridge.runScene(sc, src)
        val failed = results.count { it.second != null && it.second !is SendResult.Ok }
        return ok(buildJsonObject {
            put("status", if (failed == 0) "ok" else "partial"); put("scene", sc.id); put("failed", failed)
            putJsonArray("steps") { results.forEach { (st, r) -> addJsonObject {
                if (st.isWait) put("wait", st.delayMs) else {
                    put("device", st.device); put("button", st.button); put("ok", r is SendResult.Ok)
                    (r as? SendResult.Failed)?.let { put("error", it.message) }; (r as? SendResult.NotFound)?.let { put("error", it.message) }
                }
            } } }
        })
    }

    // ---------------------------------------------------------------- settings

    private fun settingsJson() = buildJsonObject {
        val c = bridge.settings.settings.value
        put("port", c.port)
        put("bind_address", c.bindAddress)
        put("auth", c.authMode.name.lowercase())
        put("username", c.username)
        put("has_password", c.hasPassword)
        put("allow_get", c.allowGetActions)
        put("wake_lock", c.wakeLock)
        put("auto_start", c.autoStart)
    }

    private fun saveSettings(p: Map<String, String>): Response {
        val cur = bridge.settings.settings.value
        fun bad(msg: String) = error(Response.Status.BAD_REQUEST, msg)
        // Every supplied value must parse; nothing is silently replaced by the current value.
        val port = p["port"]?.let { it.trim().toIntOrNull() ?: return bad("Invalid port '$it'") } ?: cur.port
        val bind = p["bind_address"]?.trim()?.let { a ->
            if (a != "auto" && a != "0.0.0.0" && Net.bindOptions().none { it.address == a }) return bad("Unknown address '$a'. Use one from /api/network")
            a
        } ?: cur.bindAddress
        val auth = p["auth"]?.let {
            when (it.lowercase()) { "password" -> AuthMode.PASSWORD; "guest" -> AuthMode.GUEST; else -> return bad("Invalid auth '$it'. Use guest or password") }
        } ?: cur.authMode
        val wake = p["wake_lock"]?.let { it.strictBool() ?: return bad("Invalid wake_lock '$it'") } ?: cur.wakeLock
        val auto = p["auto_start"]?.let { it.strictBool() ?: return bad("Invalid auto_start '$it'") } ?: cur.autoStart
        val allowGet = p["allow_get"]?.let { it.strictBool() ?: return bad("Invalid allow_get '$it'") } ?: cur.allowGetActions
        val next = cur.copy(
            port = port, bindAddress = bind, authMode = auth,
            username = p["username"]?.trim() ?: cur.username,
            password = p["password"]?.takeIf { it.isNotEmpty() } ?: "",
            wakeLock = wake, autoStart = auto, allowGetActions = allowGet,
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
                    put("on_screen", d.onScreen.any { it.equals(name, true) })
                    put("url", endpoints.blast(d.id, name))
                    custom[name]?.let { put("code", codeText(it.code)) }
                }
            }
        }
        val l = bridge.layout(d)
        putJsonObject("layout") {
            listOf("mute" to l.mute, "power" to l.power, "up" to l.up, "down" to l.down, "left" to l.left, "right" to l.right,
                "ok" to l.ok, "vol_down" to l.volDown, "vol_up" to l.volUp, "ch_down" to l.chDown, "ch_up" to l.chUp,
                "home" to l.home, "back" to l.back, "settings" to l.settings).forEach { (k, v) -> put(k, v) }
            putJsonArray("extras") { l.extras.forEach { add(it) } }
            putJsonArray("available") { l.available.forEach { add(it) } }
        }
        put("swipe", d.swipeDpad)
        val g = bridge.grid(d, l)
        putJsonObject("grid") {
            put("cols", g.cols); put("rows", g.rows)
            putJsonArray("tiles") { g.tiles.forEach { t -> addJsonObject { put("id", t.id); put("col", t.col); put("row", t.row); put("w", t.w); put("h", t.h) } } }
        }
        putJsonArray("favorites") { d.favorites.forEach { add(it) } }
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
    private fun String.strictBool(): Boolean? = when (lowercase()) {
        "true", "1", "on", "yes" -> true
        "false", "0", "off", "no" -> false
        else -> null
    }

    private fun params(s: IHTTPSession): Map<String, String> {
        val out = linkedMapOf<String, String>()
        if (s.method == Method.POST || s.method == Method.PUT || s.method == Method.DELETE) {
            val files = HashMap<String, String>()
            runCatching { s.parseBody(files) }
            files["postData"]?.let { body ->
                out["__body"] = body
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

    /** No Access-Control-Allow-Origin: only the bundled dashboard (same origin) and non-browser clients may call the API. */
    private fun cors(r: Response) = r.apply { addHeader("Cache-Control", "no-store") }

    private val webHtml: String? by lazy { javaClass.getResourceAsStream("/web/index.html")?.use { it.readBytes().toString(Charsets.UTF_8) } }

    private fun webUi(): Response {
        val html = webHtml ?: return error(Response.Status.NOT_FOUND, "Web UI missing")
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }
}
