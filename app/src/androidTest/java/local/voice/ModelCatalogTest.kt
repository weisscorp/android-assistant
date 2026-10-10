package local.voice

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModelCatalogTest {
    private fun isolated(name: String): Pair<Context, File> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, name).apply { deleteRecursively(); mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
            override fun getSharedPreferences(name: String?, mode: Int) = base.getSharedPreferences("$name-${root.name}", mode)
        }
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        context.getSharedPreferences("model-benchmarks", 0).edit().clear().commit()
        return context to root
    }
    @Test fun catalogHasAllPinnedArchitecturesAndMatchingRuntimeProof() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val all = BuiltinModels.all(context)
        assertEquals(7, all.size)
        assertEquals("qwen35-2b-q4km", all.first().id)
        assertEquals(all, BuiltinModels.available(context))
        assertEquals(setOf("lfm2", "qwen3", "qwen35", "gemma3"), all.map { it.architecture }.toSet())
        all.forEach {
            assertTrue(it.size in 1..ModelMetadata.MAX_CATALOG_BYTES)
            assertTrue(it.chatTemplate.isNotBlank())
            assertEquals(LocalBrain.bindingRevision, it.validatedBinding)
            assertEquals(it, ModelMetadata.decode(it.json()))
            assertTrue(it.url.contains("/resolve/${it.revision}/"))
        }
    }
    @Test fun deletingActiveModelFallsBackWithoutDeletingOtherBenchmarks() {
        val (context, root) = isolated("catalog-delete-test")
        try {
            val models = BuiltinModels.all(context).take(2)
            val first = ModelCatalog.installBrain(context, File(root,"one.import").apply { writeText("one") }, models[0].name, models[0])
            val second = ModelCatalog.installBrain(context, File(root,"two.import").apply { writeText("two") }, models[1].name, models[1])
            fun metrics(brain: ModelCatalog.Brain) = ModelBenchmark.Result(brain.id, brain.metadata!!.sha256, LocalBrain.bindingRevision,
                100, 20, 2_000_000, 4_000_000, 3_000_000, 100_000, 800_000_000, 4, 2048, 1)
            val a = metrics(first); val b = metrics(second)
            ModelBenchmark.save(context, a); ModelBenchmark.save(context, b)
            assertEquals(a, ModelBenchmark.read(context, first)); assertEquals(b, ModelBenchmark.read(context, second))
            assertEquals(50.0, a.promptTokPerSecond, .01); assertEquals(5.0, a.generationTokPerSecond, .01)
            ModelCatalog.deleteBrain(context, second)
            assertEquals(first.id, ModelCatalog.selectedBrain(context)!!.id)
            assertFalse(second.file.exists()); assertTrue(first.file.exists())
            assertNull(ModelBenchmark.read(context, second)); assertEquals(a, ModelBenchmark.read(context, first))
            ModelCatalog.deleteBrain(context, first)
            assertNull(ModelCatalog.selectedBrain(context))
            assertFalse(context.getSharedPreferences("settings",0).contains("brain_id"))
        } finally { root.deleteRecursively() }
    }
    @Test fun staleBenchmarkAndInvalidCustomGgufDoNotReplaceActiveModel() {
        val (context, root) = isolated("catalog-invalid-test")
        try {
            val model = BuiltinModels.all(context).first()
            val brain = ModelCatalog.installBrain(context, File(root,"one.import").apply { writeText("one") }, model.name, model)
            val metrics = ModelBenchmark.Result(brain.id, model.sha256, LocalBrain.bindingRevision, 100, 20,
                2_000_000, 4_000_000, 3_000_000, 100_000, 800_000_000, 4, 2048, 1)
            ModelBenchmark.save(context, metrics.copy(sha256 = "0".repeat(64)))
            assertNull(ModelBenchmark.read(context, brain))
            ModelBenchmark.save(context, metrics.copy(bindingRevision = "old-runtime"))
            assertNull(ModelBenchmark.read(context, brain))
            val corrupt = File(root,"corrupt.gguf").apply { writeBytes(byteArrayOf(71,71,85,70,1)) }
            assertTrue(runCatching { LocalBrain.inspect(corrupt) }.isFailure)
            assertEquals(brain.id, ModelCatalog.selectedBrain(context)!!.id)
            assertEquals(1, ModelCatalog.brains(context).size)
        } finally { root.deleteRecursively() }
    }
    @Test fun reimportedCatalogWeightsShowTheActuallyActiveCopy() {
        val (context, root) = isolated("catalog-duplicate-test")
        try {
            val model = BuiltinModels.all(context).first()
            val first = ModelCatalog.installBrain(context, File(root,"one.import").apply { writeText("one") }, model.name, model)
            val second = ModelCatalog.installBrain(context, File(root,"two.import").apply { writeText("two") }, model.name, model)
            assertEquals(second.id, ModelCatalog.installed(context, model)!!.id)
            ModelCatalog.selectBrain(context, first)
            assertEquals(first.id, ModelCatalog.installed(context, model)!!.id)
            assertTrue(first.file.exists()); assertTrue(second.file.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun importsPreserveExistingModelsAndSwitchWithoutRelabelingLegacy() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "model-catalog-test").apply { deleteRecursively(); mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
            override fun getSharedPreferences(name: String?, mode: Int) = base.getSharedPreferences("catalog-test-$name", mode)
        }
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("model_name", "Old Qwen").commit()
        try {
            val legacy = File(root, "model.gguf").apply { writeBytes(byteArrayOf(71,71,85,70,1)) }
            val oldBytes = legacy.readBytes()
            assertEquals("Old Qwen", ModelCatalog.selectedBrain(context)!!.name)
            val first = ModelCatalog.installBrain(context, File(root, "first.import").apply { writeBytes(byteArrayOf(71,71,85,70,2)) }, "Qwen Q4")
            val second = ModelCatalog.installBrain(context, File(root, "second.import").apply { writeBytes(byteArrayOf(71,71,85,70,3)) }, "Qwen Q8")
            assertEquals(3, ModelCatalog.brains(context).size)
            assertArrayEquals(oldBytes, legacy.readBytes())
            assertEquals(second.id, ModelCatalog.selectedBrain(context)!!.id)
            ModelCatalog.selectBrain(context, first)
            assertEquals(first.file, LocalBrain.model(context))
            val old = ModelCatalog.brains(context).single { it.id == "legacy" }
            assertEquals("Old Qwen", old.name)
            ModelCatalog.selectBrain(context, old)
            assertEquals(legacy, LocalBrain.model(context))
            assertTrue(first.file.exists()); assertTrue(second.file.exists())
        } finally { prefs.edit().clear().commit(); root.deleteRecursively() }
    }
}
