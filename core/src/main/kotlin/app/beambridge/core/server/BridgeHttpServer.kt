package app.beambridge.core.server

import app.beambridge.core.data.AcState
import app.beambridge.core.data.SavedDevice
import app.beambridge.core.ir.IrCode
import app.beambridge.core.ir.IrCodeEncoder
import app.beambridge.core.ir.IrEncodingException
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.*
import java.net.URLDecoder

/**
 * Embedded LAN HTTP server.
 *
 *  GET  /                         web dashboard
 *  GET  /api/status               bridge status
 *  GET  /api/devices              devices, buttons and their invocation URLs
 *  GET  /api/devices/{id}         one device
 *  GET|POST /api/blast            device=…&button=…            → {"status":"ok"}
 *  POST /api/raw                  frequency=…&pattern=9000,4500,… | pronto=… | protocol=…&code=…[&bits=…]
 *  GET|POST /api/ac               device=…[&power=on|off][&mode=cool][&temp=24][&fan=auto]
 *
 * Parameters may come from the query string, a form body, or a JSON body.
 */
class BridgeHttpServer(
    hostname: String?,
    port: Int,
    private val controller: BridgeController,
    private val endpoints: Endpoints,
) : NanoHTTPD(hostname, port) {

    private val json = Json { prettyPrint = false }

    fun startServer() = start(SOCKET_READ_TIMEOUT, true)

    override fun serve(session: IHTTPSession): Response = try {
        if (session.method == Method.OPTIONS) cors(newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, ""))
        else cors(route(session))
    } catch (e: Exception) {
        cors(error(Response.Status.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName))
    }

    private fun route(s: IHTTPSession): Response {
        val path = s.uri.trimEnd('/').ifEmpty { "/" }
        return when {
            path == "/" || path == "/index.html" -> webUi()
            path == "/api/status" -> ok(status())
            path == "/api/devices" -> ok(JsonArray(controller.store.devices.value.map(::deviceJson)))
            path.startsWith("/api/devices/") -> {
                val id = URLDecoder.decode(path.removePrefix("/api/devices/"), "UTF-8")
                controller.store.get(id)?.let { ok(deviceJson(it)) } ?: error(Response.Status.NOT_FOUND, "Unknown device '$id'")
            }
            path == "/api/blast" -> requireMethod(s, Method.GET, Method.POST) ?: blast(params(s))
            path == "/api/raw" -> requireMethod(s, Method.POST, Method.GET) ?: raw(params(s))
            path == "/api/ac" -> requireMethod(s, Method.GET, Method.POST) ?: ac(params(s))
            else -> error(Response.Status.NOT_FOUND, "No route for ${s.uri}")
        }
    }

    // ----------------------------------------------------------- handlers

    private fun status() = buildJsonObject {
        put("status", "ok")
        put("app", "BeamBridge")
        put("ir_emitter", controller.emitterAvailable)
        put("base_url", endpoints.base())
        put("devices", controller.store.devices.value.size)
    }

    private fun blast(p: Map<String, String>): Response {
        val device = p["device"] ?: return error(Response.Status.BAD_REQUEST, "Missing 'device'")
        val button = p["button"] ?: return error(Response.Status.BAD_REQUEST, "Missing 'button'")
        return result(controller.blast(device, button)) { put("device", device); put("button", button) }
    }

    private fun raw(p: Map<String, String>): Response {
        val code: IrCode = try {
            when {
                p["pronto"] != null -> IrCode.Pronto(p.getValue("pronto"))
                p["code"] != null || p["hex"] != null -> IrCode.Hex(
                    protocol = p["protocol"] ?: "NEC",
                    code = p["code"] ?: p.getValue("hex"),
                    bits = p["bits"]?.toIntOrNull() ?: 0,
                    frequency = p["frequency"]?.toIntOrNull(),
                )
                p["pattern"] != null -> IrCode.Raw(
                    frequency = p["frequency"]?.toIntOrNull() ?: return error(Response.Status.BAD_REQUEST, "Missing or bad 'frequency'"),
                    pattern = IrCodeEncoder.parsePattern(p.getValue("pattern")),
                )
                else -> return error(Response.Status.BAD_REQUEST, "Provide 'frequency'+'pattern', 'pronto', or 'protocol'+'code'")
            }
        } catch (e: IrEncodingException) {
            return error(Response.Status.BAD_REQUEST, e.message ?: "Bad code")
        }
        return result(controller.send(code))
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

    // ----------------------------------------------------------- JSON

    private fun deviceJson(d: SavedDevice) = buildJsonObject {
        put("id", d.id)
        put("name", d.name)
        put("type", d.type.name.lowercase())
        d.brand?.let { put("brand", it) }
        put("endpoint", endpoints.device(d.id))
        putJsonArray("buttons") {
            val custom = d.buttons.filter { it.custom }.map { it.name }.toSet()
            controller.buttonNames(d).forEach { name ->
                addJsonObject {
                    put("name", name)
                    put("custom", name in custom)
                    put("url", endpoints.blast(d.id, name))
                }
            }
        }
        d.acState?.let { put("ac_state", acJson(it)); put("ac_url", endpoints.ac(d.id, it)) }
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
        is SendResult.Failed -> error(Response.Status.lookup(422) ?: Response.Status.BAD_REQUEST, r.message)
    }

    // ----------------------------------------------------------- plumbing

    private fun params(s: IHTTPSession): Map<String, String> {
        val out = linkedMapOf<String, String>()
        if (s.method == Method.POST || s.method == Method.PUT) {
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
        // Query-string / form params (NanoHTTPD merges both after parseBody). JSON body wins on conflicts.
        s.parameters.forEach { (k, v) -> if (k !in out && v.isNotEmpty()) out[k] = v.first() }
        return out
    }

    private fun requireMethod(s: IHTTPSession, vararg allowed: Method): Response? =
        if (s.method in allowed) null else error(Response.Status.METHOD_NOT_ALLOWED, "Use ${allowed.joinToString("/")}")

    private fun ok(body: JsonElement) = newFixedLengthResponse(Response.Status.OK, "application/json", json.encodeToString(JsonElement.serializer(), body))

    private fun error(status: Response.IStatus, message: String) = newFixedLengthResponse(
        status, "application/json",
        json.encodeToString(JsonElement.serializer(), buildJsonObject { put("status", "error"); put("error", message) }),
    )

    private fun cors(r: Response) = r.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Content-Type")
        addHeader("Cache-Control", "no-store")
    }

    private fun webUi(): Response {
        val html = javaClass.getResourceAsStream("/web/index.html")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return error(Response.Status.NOT_FOUND, "Web UI missing")
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }
}
