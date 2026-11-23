package icu.nullptr.polyglot.youtube

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.util.SparseArray
import android.view.View
import icu.nullptr.polyglot.captions.BilingualFormatter
import icu.nullptr.polyglot.captions.CaptionCue
import icu.nullptr.polyglot.captions.CaptionSession
import icu.nullptr.polyglot.captions.engine.CaptionPipelineCoordinator
import icu.nullptr.polyglot.module
import icu.nullptr.polyglot.util.hook
import icu.nullptr.polyglot.util.logD
import icu.nullptr.polyglot.util.logI
import icu.nullptr.polyglot.util.logV
import icu.nullptr.polyglot.util.logW
import icu.nullptr.polyglot.util.toMethod
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

object CaptionHook : BaseHook {
    override val name = "CaptionHook"
    override val totalHooks = 4

    private val session = CaptionSession()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rendererStates = WeakHashMap<Any, RendererState>()
    private val rendererSequence = AtomicLong(0L)
    private val applyingTranslatedText = ThreadLocal.withInitial { false }

    private val coordinator = CaptionPipelineCoordinator(
        session = session,
        clockMs = SystemClock::elapsedRealtime,
        logger = { msg, tr -> logW(name, msg, tr) },
        onRefreshUi = ::refreshVisibleRenderers,
    )

    override fun install(dexkit: DexKitBridge): Int {
        var installed = 0

        if (installTimelineBuildHook(dexkit)) {
            installed++
        }

        if (installOverlayUpdateHooks(dexkit)) {
            installed++
        }

        if (installRenderTextHooks(dexkit)) {
            installed++
        }

        if (installCaptionLanguageHooks(dexkit)) {
            installed++
        }

        logI(name, "Installed $installed caption hook(s)")
        return installed
    }

    fun formatCaption(original: CharSequence?): CharSequence {
        val resolved = coordinator.resolveForRender(original)
        return BilingualFormatter.format(original, resolved?.translated)
    }

    fun onTranslationCacheCleared() {
        coordinator.onTranslationCacheCleared()
    }

    private fun installTimelineBuildHook(dexkit: DexKitBridge): Boolean {
        val method = dexkit.findCaptionTimelineBuildMethod()
            ?.toMethod() ?: run {
            logW(name, "Caption timeline builder not found")
            return false
        }

        hook(method) { chain ->
            val result = chain.proceed()
            val extractedCues = extractTimelineCues(result)
            val plannedBlocks = coordinator.onTimelineBuilt(extractedCues)
            if (plannedBlocks.isNotEmpty()) {
                logObservedCueCount(extractedCues.size, source = "timeline(${plannedBlocks.size} blocks)")
            }
            result
        }

        logD(name, "Hooked caption timeline builder: ${method.shortName()}")
        return true
    }

    private fun installRenderTextHooks(dexkit: DexKitBridge): Boolean {
        val methods = dexkit.findCaptionRenderTextMethods()
            .map { it.toMethod() }
            .filter { method ->
                View::class.java.isAssignableFrom(method.declaringClass) &&
                    method.declaringClass.hasInstanceFieldAssignableTo(Editable::class.java)
            }
            .distinctBy { it.stableId() }

        var installed = 0
        for (method in methods) {
            hook(method) { chain ->
                if (applyingTranslatedText.get() == true) {
                    return@hook chain.proceed()
                }

                if (!coordinator.syncRuntimeState()) {
                    return@hook chain.proceed()
                }

                val thisObject = chain.thisObject ?: return@hook chain.proceed()
                if (!thisObject.javaClass.hasInstanceFieldAssignableTo(Editable::class.java)) {
                    return@hook chain.proceed()
                }

                val original = chain.getArg(0) as? CharSequence
                    ?: return@hook chain.proceed()
                val rawString = original.toString()
                val normalizedOriginal = CaptionCue.normalize(rawString)
                if (normalizedOriginal.isEmpty()) {
                    return@hook chain.proceed()
                }

                rememberRendererState(thisObject, method, rawString, normalizedOriginal)

                if (session.observeRenderedText(original)) {
                    logV(name, "Observed rendered caption text, length=${normalizedOriginal.length}")
                }

                val resolved = coordinator.resolveForRender(original)
                    ?: return@hook chain.proceed()

                val isMultiViewFragment = '\n' !in rawString &&
                    '\r' !in rawString &&
                    resolved.original != normalizedOriginal &&
                    resolved.original.contains(normalizedOriginal)

                if (isMultiViewFragment) {
                    val replacement = replacementForTranslatedCue(
                        cueText = resolved.original,
                        renderedFragment = normalizedOriginal,
                        translated = resolved.translated,
                    )
                    return@hook chain.proceed(arrayOf(replacement))
                }

                val formatted = BilingualFormatter.format(original, resolved.translated)
                if (formatted === original || formatted.toString() == rawString) {
                    chain.proceed()
                } else {
                    chain.proceed(arrayOf(formatted))
                }
            }

            logD(name, "Hooked caption renderer: ${method.shortName()}")
            installed++
        }

        if (installed == 0) {
            logW(name, "Caption renderer not found")
        }
        return installed > 0
    }

    private fun installCaptionLanguageHooks(dexkit: DexKitBridge): Boolean {
        val trackClassName = dexkit.findCaptionTrackClassName() ?: run {
            logW(name, "Caption track class not found")
            return false
        }

        val methods = dexkit.findCaptionTrackStateMethods(trackClassName)
            .map { it.toMethod() }
            .distinctBy { it.stableId() }

        if (methods.isEmpty()) {
            logW(name, "Caption language methods not found")
            return false
        }

        for (method in methods) {
            hook(method) { chain ->
                observeCaptionTrack(chain.getArg(0), "${method.shortName()} arg0")
                chain.proceed()
            }
        }

        logD(name, "Hooked caption language trackers: ${methods.size}")
        return true
    }

    private fun installOverlayUpdateHooks(dexkit: DexKitBridge): Boolean {
        val methods = dexkit.findCaptionOverlayUpdateMethods()
            .map { it.toMethod() }
            .filter { method ->
                View::class.java.isAssignableFrom(method.declaringClass) &&
                    method.declaringClass.hasInstanceFieldAssignableTo(SparseArray::class.java)
            }
            .distinctBy { it.stableId() }

        var installed = 0
        for (method in methods) {
            hook(method) { chain ->
                val rawCues = extractOverlayCueList(chain.getArg(0))
                if (rawCues.isNotEmpty()) {
                    coordinator.onOverlayCues(rawCues)
                }
                chain.proceed()
            }

            logD(name, "Hooked caption overlay update: ${method.shortName()}")
            installed++
        }

        if (installed == 0) {
            logW(name, "Caption overlay update methods not found")
        }
        return installed > 0
    }

    private fun extractTimelineCues(result: Any?): List<CaptionCue> {
        if (result == null) return emptyList()

        val lists = result.instanceFieldValues()
            .filterIsInstance<List<*>>()
            .filter { it.isNotEmpty() }
        val texts = lists.firstOrNull { list -> list.any { it is CharSequence } } ?: return emptyList()
        val timeLists = lists.filter { list -> list.all { it is Number } }
        if (timeLists.size < 2) return emptyList()

        val (starts, ends) = orderedTimelineTimeLists(timeLists[0], timeLists[1])

        val size = minOf(starts.size, ends.size, texts.size)
        if (size == 0) return emptyList()

        val cues = ArrayList<CaptionCue>(size)
        for (index in 0 until size) {
            val text = texts[index] as? CharSequence ?: continue
            val normalized = CaptionCue.normalize(text.toString())
            if (normalized.isEmpty()) continue

            cues.add(
                CaptionCue(
                    videoId = "",
                    startMs = (starts[index] as? Number)?.toLong() ?: CaptionCue.UNKNOWN_TIME_MS,
                    endMs = (ends[index] as? Number)?.toLong() ?: CaptionCue.UNKNOWN_TIME_MS,
                    text = normalized,
                ),
            )
        }
        return cues
    }

    private fun extractOverlayCueList(arg: Any?): List<CaptionCue> {
        val list = arg as? List<*> ?: return emptyList()
        return list.mapNotNull { item -> item?.toCaptionCue() }
    }

    private fun Any.toCaptionCue(): CaptionCue? {
        val text = instanceFieldValues()
            .filterIsInstance<CharSequence>()
            .map { CaptionCue.normalize(it.toString()) }
            .filter { it.isNotEmpty() }
            .maxByOrNull { it.length }
            ?: return null

        return CaptionCue(
            videoId = "",
            startMs = firstInstanceFieldValue<Long>() ?: CaptionCue.UNKNOWN_TIME_MS,
            endMs = CaptionCue.UNKNOWN_TIME_MS,
            text = text,
            windowId = firstInstanceFieldValue<Int>() ?: CaptionCue.UNKNOWN_WINDOW_ID,
        )
    }

    private fun orderedTimelineTimeLists(first: List<*>, second: List<*>): Pair<List<*>, List<*>> {
        val comparableSize = minOf(first.size, second.size)
        var firstBeforeSecond = 0
        var secondBeforeFirst = 0
        for (index in 0 until comparableSize) {
            val a = (first[index] as? Number)?.toLong() ?: continue
            val b = (second[index] as? Number)?.toLong() ?: continue
            if (a <= b) firstBeforeSecond++ else secondBeforeFirst++
        }
        return if (firstBeforeSecond >= secondBeforeFirst) {
            first to second
        } else {
            second to first
        }
    }

    private inline fun <reified T> Any.firstInstanceFieldValue(): T? =
        instanceFieldValues().firstNotNullOfOrNull { it as? T }

    private fun Any.instanceFieldValues(): List<Any?> {
        val values = ArrayList<Any?>()
        var clazz: Class<*>? = javaClass
        while (clazz != null) {
            for (field in clazz.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                values += field.getValueOrNull(this)
            }
            clazz = clazz.superclass
        }
        return values
    }

    private fun Field.getValueOrNull(instance: Any): Any? =
        runCatching {
            isAccessible = true
            get(instance)
        }.getOrNull()

    private fun Class<*>.hasInstanceFieldAssignableTo(type: Class<*>): Boolean {
        var clazz: Class<*>? = this
        while (clazz != null) {
            for (field in clazz.declaredFields) {
                if (!Modifier.isStatic(field.modifiers) && type.isAssignableFrom(field.type)) {
                    return true
                }
            }
            clazz = clazz.superclass
        }
        return false
    }

    private fun logObservedCueCount(cueCount: Int, source: String) {
        if (cueCount <= 0) return

        val total = session.observedCueCount()
        if (total <= 5 || total % 25 == 0 || source.startsWith("timeline")) {
            logV(name, "Observed $cueCount caption cue(s) from $source, total=$total")
        }
    }

    private fun observeCaptionTrack(track: Any?, source: String) {
        if (!coordinator.onCaptionTrackObserved(track, source)) return

        synchronized(rendererStates) {
            rendererStates.clear()
        }
        rendererSequence.set(0L)
    }

    private fun rememberRendererState(
        renderer: Any,
        method: Method,
        rawText: String,
        normalizedText: String,
    ) {
        if (normalizedText.isEmpty()) return

        synchronized(rendererStates) {
            rendererStates[renderer] = RendererState(
                method = method,
                rawText = rawText,
                normalizedText = normalizedText,
                sequence = rendererSequence.incrementAndGet(),
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    private fun replacementForTranslatedCue(
        cueText: String,
        renderedFragment: String,
        translated: String,
    ): CharSequence {
        val normalizedCue = CaptionCue.normalize(cueText)
        val normalizedFragment = CaptionCue.normalize(renderedFragment)
        if (normalizedCue.isEmpty() || normalizedFragment.isEmpty()) return renderedFragment

        val shouldDisplayBlock = normalizedCue == normalizedFragment ||
            normalizedCue.endsWith(normalizedFragment)
        return if (shouldDisplayBlock) {
            BilingualFormatter.format(normalizedCue, translated)
        } else {
            ""
        }
    }

    private fun refreshVisibleRenderers(
        updatedOriginals: Set<String>,
        expectedEpoch: Long,
        source: String,
    ) {
        mainHandler.post {
            if (!module.config.enabled || expectedEpoch != session.currentEpoch()) {
                return@post
            }

            val now = System.currentTimeMillis()
            val snapshot = synchronized(rendererStates) {
                rendererStates.entries
                    .filter { (_, state) -> now - state.updatedAtMs <= RENDERER_STATE_TTL_MS }
                    .map { (renderer, state) -> renderer to state }
            }
            if (snapshot.isEmpty()) return@post

            // Full refresh (e.g. cache cleared or target language changed): re-evaluate or restore original text.
            if (updatedOriginals.isEmpty()) {
                for ((renderer, state) in snapshot) {
                    val resolved = session.resolveTranslation(state.rawText)
                    val display = if (resolved != null) {
                        BilingualFormatter.format(state.rawText, resolved.translated)
                    } else {
                        state.rawText
                    }
                    invokeRenderer(renderer, state.method, display, source, state.normalizedText.length)
                }
                return@post
            }

            val handledRenderers = HashSet<Any>()
            for ((renderer, state) in snapshot) {
                val matchesExact = state.normalizedText in updatedOriginals
                val matchesRollupLine = ('\n' in state.rawText || '\r' in state.rawText) &&
                    CaptionCue.normalizeLines(state.rawText).any { it in updatedOriginals }

                if (matchesExact || matchesRollupLine) {
                    val resolved = session.resolveTranslation(state.rawText) ?: continue
                    val formatted = BilingualFormatter.format(state.rawText, resolved.translated)
                    if (formatted.toString() != state.rawText) {
                        invokeRenderer(renderer, state.method, formatted, source, state.normalizedText.length)
                        handledRenderers.add(renderer)
                    }
                }
            }

            for (updatedCue in updatedOriginals) {
                if (updatedCue.isEmpty()) continue
                val coveredTargets = snapshot
                    .filter { (renderer, state) ->
                        renderer !in handledRenderers &&
                            state.normalizedText.isNotEmpty() &&
                            state.normalizedText != updatedCue &&
                            updatedCue.contains(state.normalizedText)
                    }
                    .sortedBy { (_, state) -> state.sequence }
                if (coveredTargets.isEmpty()) continue

                val translated = session.translationFor(updatedCue) ?: continue
                val formatted = BilingualFormatter.format(updatedCue, translated)
                if (formatted.toString() == updatedCue) continue

                for ((renderer, state) in coveredTargets.dropLast(1)) {
                    invokeRenderer(renderer, state.method, "", source, updatedCue.length)
                    handledRenderers.add(renderer)
                }
                val anchor = coveredTargets.last()
                invokeRenderer(anchor.first, anchor.second.method, formatted, source, updatedCue.length)
                handledRenderers.add(anchor.first)
            }
        }
    }

    private fun invokeRenderer(
        renderer: Any,
        method: Method,
        text: CharSequence,
        source: String,
        originalLength: Int,
    ) {
        runCatching {
            applyingTranslatedText.set(true)
            method.invoke(renderer, text)
        }.onSuccess {
            if (text.isNotEmpty()) {
                logV(name, "Refreshed visible caption from $source, length=$originalLength")
            }
        }.onFailure { e ->
            logW(name, "Unable to refresh visible caption", e)
        }.also {
            applyingTranslatedText.set(false)
        }
    }

    private fun Executable.shortName(): String =
        "${declaringClass.name}#$name(${parameterTypes.joinToString { it.simpleName }})"

    private fun Executable.stableId(): String =
        "${declaringClass.name}#$name#${parameterTypes.joinToString { it.name }}"

    private fun DexKitBridge.findCaptionTimelineBuildMethod(): MethodData? {
        val addLineMethod = findMethod {
            matcher {
                returnType("void")
                paramTypes("java.lang.CharSequence", "long", "long")
                usingEqStrings(NON_DECREASING_SUBTITLE_TIME_ERROR)
            }
        }.singleOrNull()

        val builderClass = addLineMethod?.declaredClass ?: return null
        val candidates = builderClass.methods.filter { method ->
            method.isMethod &&
                method.paramCount == 0 &&
                method.returnTypeName != "void" &&
                method.returnType?.hasAtLeastListFields(count = 3) == true
        }

        return candidates.singleOrNull() ?: candidates.firstOrNull()
    }

    private fun ClassData.hasAtLeastListFields(count: Int): Boolean =
        fields.count { !Modifier.isStatic(it.modifiers) && it.typeName == "java.util.List" } >= count

    private fun ClassData.hasInstanceFieldTypeInHierarchy(typeName: String): Boolean {
        var clazz: ClassData? = this
        while (clazz != null) {
            if (clazz.fields.any { !Modifier.isStatic(it.modifiers) && it.typeName == typeName }) {
                return true
            }
            clazz = clazz.superClass
        }
        return false
    }

    private fun DexKitBridge.findCaptionRenderTextMethods(): List<MethodData> =
        findMethod {
            matcher {
                returnType("void")
                paramTypes("java.lang.CharSequence")
            }
        }.filter { method ->
            method.declaredClass?.hasInstanceFieldTypeInHierarchy(EDITABLE_TYPE) == true &&
                !method.isConstructor
        }

    private fun DexKitBridge.findCaptionOverlayUpdateMethods(): List<MethodData> =
        findMethod {
            matcher {
                returnType("void")
                paramTypes("java.util.List")
            }
        }.filter { method ->
            method.declaredClass?.hasInstanceFieldTypeInHierarchy(SPARSE_ARRAY_TYPE) == true &&
                !method.isConstructor
        }

    private fun DexKitBridge.findCaptionTrackClassName(): String? =
        findMethod {
            matcher {
                returnType("boolean")
                usingEqStrings(AUTO_TRANSLATE_CAPTIONS_OPTION)
            }
        }.singleOrNull { method -> method.isMethod && method.paramCount == 0 }
            ?.toMethod()
            ?.declaringClass
            ?.name

    private fun DexKitBridge.findCaptionTrackStateMethods(trackClassName: String): List<MethodData> =
        findMethod {
            matcher {
                returnType("void")
                paramTypes(trackClassName)
                usingEqStrings(MENU_ITEM_CAPTIONS_KEY)
            }
        }.filter { method ->
            method.isMethod &&
                !Modifier.isAbstract(method.modifiers)
        }

    private const val NON_DECREASING_SUBTITLE_TIME_ERROR =
        "subtitles are not given in non-decreasing start time order"
    private const val AUTO_TRANSLATE_CAPTIONS_OPTION = "AUTO_TRANSLATE_CAPTIONS_OPTION"
    private const val MENU_ITEM_CAPTIONS_KEY = "menu_item_captions"
    private const val EDITABLE_TYPE = "android.text.Editable"
    private const val SPARSE_ARRAY_TYPE = "android.util.SparseArray"
    private const val RENDERER_STATE_TTL_MS = 2_500L

    private data class RendererState(
        val method: Method,
        val rawText: String,
        val normalizedText: String,
        val sequence: Long,
        val updatedAtMs: Long,
    )
}
