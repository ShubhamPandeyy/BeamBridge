package app.beambridge.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A scene step: press [button] on [device], or (when [device] is blank) just wait [delayMs]. */
@Serializable
data class SceneStep(val device: String = "", val button: String = "", val delayMs: Long = 0) {
    val isWait get() = device.isBlank()
}

@Serializable
data class Scene(val id: String, val name: String, val steps: List<SceneStep> = emptyList())

interface SceneStore {
    val scenes: StateFlow<List<Scene>>
    fun get(id: String): Scene? = scenes.value.firstOrNull { it.id.equals(id, true) } ?: scenes.value.firstOrNull { it.name.equals(id, true) }
    fun save(scene: Scene): Scene
    fun remove(id: String): Boolean
    fun newId(name: String) = Slug.unique(name, scenes.value.map { it.id })
}

class InMemorySceneStore : SceneStore {
    private val state = MutableStateFlow<List<Scene>>(emptyList())
    override val scenes: StateFlow<List<Scene>> = state.asStateFlow()
    @Synchronized override fun save(scene: Scene): Scene { state.value = state.value.filterNot { it.id == scene.id } + scene; return scene }
    @Synchronized override fun remove(id: String): Boolean { val s = get(id) ?: return false; state.value = state.value - s; return true }
}

class JsonFileSceneStore(private val file: File) : SceneStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val serializer = ListSerializer(Scene.serializer())
    private val state = MutableStateFlow(SafeFile.read(file, emptyList()) { json.decodeFromString(serializer, it) })
    override val scenes: StateFlow<List<Scene>> = state.asStateFlow()

    private fun commit(list: List<Scene>) {
        SafeFile.write(file, json.encodeToString(serializer, list))
        state.value = list
    }

    @Synchronized override fun save(scene: Scene): Scene {
        val list = state.value.toMutableList()
        val i = list.indexOfFirst { it.id == scene.id }
        if (i >= 0) list[i] = scene else list += scene
        commit(list); return scene
    }

    @Synchronized override fun remove(id: String): Boolean { val s = get(id) ?: return false; commit(state.value - s); return true }
}

/** Text form of scene steps, used by forms and the app editor: "tv:POWER, wait:1500, ac:POWER_ON". */
object SceneText {
    fun parse(text: String): List<SceneStep> = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
        val (a, b) = part.split(':', limit = 2).map { it.trim() }.let { it[0] to it.getOrElse(1) { "" } }
        if (a.equals("wait", true)) SceneStep(delayMs = b.removeSuffix("ms").trim().toLongOrNull() ?: throw IllegalArgumentException("Bad wait '$b'"))
        else { if (b.isEmpty()) throw IllegalArgumentException("Step '$part' should be device:BUTTON"); SceneStep(a, b.uppercase()) }
    }

    fun format(steps: List<SceneStep>) = steps.joinToString(", ") { if (it.isWait) "wait:${it.delayMs}" else "${it.device}:${it.button}" }
}
