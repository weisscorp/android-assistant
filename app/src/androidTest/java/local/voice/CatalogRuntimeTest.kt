package local.voice

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual weights, Jinja template, tokenizer, CPU tensor operations, grammar and benchmark through production JNI. */
class CatalogRuntimeTest {
    @Test fun actualArtifactRunsThroughAndroidBindingAndPersistsOwnBenchmark() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val id = InstrumentationRegistry.getArguments().getString("catalog_model") ?: error("Use verify_model_catalog.py")
        val entry = BuiltinModels.all(context).single { it.id == id }
        val file = File(context.filesDir, "catalog-tests/$id.gguf")
        assertEquals(entry.size, file.length())
        assertEquals(entry.sha256, ModelMetadata.digest(file))
        val prefs = context.getSharedPreferences("settings", 0)
        val original = prefs.all.toMap()
        var brain: ModelCatalog.Brain? = null
        try {
            val checked = CountDownLatch(1)
            var metadata: Result<GgufMetadata>? = null
            instrumentation.runOnMainSync { LocalBrain.validate(file, entry.contextSize) { metadata = it; checked.countDown() } }
            assertTrue("Native model validation timed out", checked.await(190, TimeUnit.SECONDS))
            val info = metadata!!.getOrThrow()
            assertEquals(entry.architecture, info.architecture)
            assertEquals(entry.chatTemplate, info.chatTemplate)
            assertTrue(info.bindingRevision.matches(Regex("d81235049384534c167caea52b85a694f6103d14:jni-[0-9a-f]{16}")))
            assertEquals(LocalBrain.bindingRevision, info.bindingRevision)
            assertEquals(entry.contextSize, info.contextSize)
            brain = ModelCatalog.installBrain(context, file, entry.name, entry.copy(validatedBinding = info.bindingRevision))
            val proposalDone = CountDownLatch(1)
            var proposal: LocalBrain.Proposal? = null
            var raw = ""
            instrumentation.runOnMainSync { LocalBrain.plan(context, emptyList(), "Сколько заряда батареи?", rawObserver = { raw = it }) { proposal = it; proposalDone.countDown() } }
            assertTrue("Constrained production generation timed out", proposalDone.await(100, TimeUnit.SECONDS))
            assertNull(LocalBrain.lastFailure, LocalBrain.lastFailure)
            println("CATALOG_STRUCTURED_RAW: $id · $raw")
            val decoded = JSONObject(raw)
            assertEquals(2, decoded.length())
            assertEquals("battery", decoded.getString("tool"))
            assertEquals("", decoded.getString("argument"))
            assertNotNull(proposal)
            assertEquals(VoiceCommand.Battery, proposal!!.command)
            val benchmarkDone = CountDownLatch(1)
            var result: Result<ModelBenchmark.Result>? = null
            instrumentation.runOnMainSync { LocalBrain.runBenchmark(context, brain!!, entry.recommendedThreads) { result = it; benchmarkDone.countDown() } }
            assertTrue("Native benchmark timed out", benchmarkDone.await(190, TimeUnit.SECONDS))
            val metrics = result!!.getOrThrow()
            assertTrue(metrics.promptTokPerSecond > 0)
            assertTrue(metrics.generationTokPerSecond > 0)
            assertTrue(metrics.ttftMicros >= metrics.loadMicros)
            assertTrue(metrics.peakRamBytes > 10 * 1024 * 1024)
            assertEquals(brain!!.id, metrics.modelId)
            assertEquals(entry.sha256, metrics.sha256)
            assertEquals(metrics, ModelBenchmark.read(context, brain!!))
            assertFalse(LocalBrain.modelResident)
            val cancelled = CountDownLatch(1)
            var cancelledResult: Result<ModelBenchmark.Result>? = null
            instrumentation.runOnMainSync {
                LocalBrain.runBenchmark(context, brain!!, entry.recommendedThreads) { cancelledResult = it; cancelled.countDown() }
                LocalBrain.cancel()
            }
            assertTrue(cancelled.await(190, TimeUnit.SECONDS))
            assertTrue(cancelledResult!!.isFailure)
            assertEquals("Cancellation overwrote completed benchmark", metrics, ModelBenchmark.read(context, brain!!))
            val report = JSONObject().put("id", entry.id).put("architecture", info.architecture)
                .put("sha256", entry.sha256).put("chatTemplateMatched", true).put("bindingRevision", info.bindingRevision)
                .put("loadAndDecodePassed", true).put("structuredProposal", proposal.toString()).put("benchmark", metrics.json())
                .put("cancelledBenchmarkDidNotOverwrite", true)
            File(context.cacheDir, "catalog-runtime-$id.json").writeText(report.toString(2))
            println("CATALOG_RUNTIME_PASSED: $id · ${info.architecture} · pp=${metrics.promptTokPerSecond} · tg=${metrics.generationTokPerSecond}")
        } finally {
            instrumentation.runOnMainSync { LocalBrain.cancel(); LocalBrain.trimIdle() }
            brain?.let { ModelCatalog.deleteBrain(context, it) }
            file.delete()
            val restore = prefs.edit().clear()
            original.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value); is Boolean -> restore.putBoolean(key, value)
                is Int -> restore.putInt(key, value); is Long -> restore.putLong(key, value); is Float -> restore.putFloat(key, value)
                is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            restore.commit()
        }
    }
}
