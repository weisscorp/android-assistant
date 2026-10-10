package local.voice

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit ADB setup only. Never runs as part of the default suite or touches other model files. */
class PhoneModelSetupTest {
    @Test fun installValidateActivateAndBenchmarkRequestedLfm() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("install_catalog_model") == "lfm25-12b-qad-q4")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val entry = BuiltinModels.find(context, "lfm25-12b-qad-q4")!!
        val staged = File(context.cacheDir, "setup-lfm-qad.gguf")
        var brain = ModelCatalog.installed(context, entry)
        val file = brain?.file ?: staged
        assertEquals(entry.size, file.length()); assertEquals(entry.sha256, ModelMetadata.digest(file))
        val validationDone = CountDownLatch(1)
        var validation: Result<GgufMetadata>? = null
        instrumentation.runOnMainSync { VoiceConversation.stopActive(); LocalBrain.validate(file, entry.contextSize) { validation = it; validationDone.countDown() } }
        assertTrue(validationDone.await(190, TimeUnit.SECONDS))
        val info = validation!!.getOrThrow()
        assertEquals(entry.architecture, info.architecture); assertEquals(entry.chatTemplate, info.chatTemplate)
        assertEquals(entry.validatedBinding, info.bindingRevision)
        if (brain == null) brain = ModelCatalog.installBuiltin(context, staged, entry)
        val installed = brain!!
        val prefs = context.getSharedPreferences("settings", 0)
        val oldId = prefs.getString("brain_id", null); val oldName = prefs.getString("model_name", null)
        var successful = false
        try {
            ModelCatalog.selectBrain(context, installed)
            val commandDone = CountDownLatch(1)
            var proposal: LocalBrain.Proposal? = null
            var raw = ""
            instrumentation.runOnMainSync { LocalBrain.plan(context, emptyList(), "Сколько заряда батареи?", rawObserver = { raw = it }) { proposal = it; commandDone.countDown() } }
            assertTrue(commandDone.await(100, TimeUnit.SECONDS)); assertNull(LocalBrain.lastFailure)
            assertEquals(VoiceCommand.Battery, proposal!!.command)
            assertEquals("battery", JSONObject(raw).getString("tool"))
            val benchmarkDone = CountDownLatch(1)
            var metrics: Result<ModelBenchmark.Result>? = null
            instrumentation.runOnMainSync { LocalBrain.runBenchmark(context, installed, entry.recommendedThreads) { metrics = it; benchmarkDone.countDown() } }
            assertTrue(benchmarkDone.await(190, TimeUnit.SECONDS))
            val measured = metrics!!.getOrThrow()
            assertEquals(measured, ModelBenchmark.read(context, installed))
            assertEquals(installed.id, ModelCatalog.selectedBrain(context)!!.id)
            File(context.cacheDir, "phone-lfm-setup.json").writeText(JSONObject().put("modelId", entry.id)
                .put("sha256", entry.sha256).put("activeBrainId", installed.id).put("bindingRevision", info.bindingRevision)
                .put("structuredProposal", proposal.toString()).put("benchmark", measured.json()).toString(2))
            successful = true
        } finally {
            instrumentation.runOnMainSync { LocalBrain.cancel(); LocalBrain.trimIdle() }
            if (!successful) prefs.edit().apply {
                if (oldId == null) remove("brain_id") else putString("brain_id", oldId)
                if (oldName == null) remove("model_name") else putString("model_name", oldName)
            }.commit()
        }
    }
}
