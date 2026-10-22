package local.voice

import android.content.ComponentName
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Installs/uninstalls only the synthetic fixture staged on the disposable emulator. */
class AppKnowledgeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val pkg = "local.voice.fixture"
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes().toString(Charsets.UTF_8) }
    }
    private fun waitFor(description: String, predicate: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) { if (predicate()) return; Thread.sleep(50) }
        fail(description)
    }
    @Test fun installChangeUninstallAndReinstallUpdateIndexedKnowledgeAndKeepUserNames() {
        val catalog = AppKnowledge(context)
        catalog.ensureReady()
        if (catalog.get(pkg) != null) {
            assertTrue(shell("pm uninstall $pkg").contains("Success"))
            waitFor("Removal was not reflected") { catalog.get(pkg) == null }
        }
        val scans = AppKnowledge.scanCount
        assertTrue(shell("pm install -r /data/local/tmp/app-knowledge-fixture.apk").contains("Success"))
        waitFor("PACKAGE_ADDED did not add the new app") { catalog.get(pkg) != null }
        assertEquals("A package event scanned every installed app", scans, AppKnowledge.scanCount)
        val installed = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toSet()
        assertEquals(installed, catalog.list().map { it.pkg }.toSet())

        catalog.saveNames(pkg, "Моя библиотека", listOf("Читалка", "рабочий читатель"))
        assertEquals(pkg, AppKnowledge(context).matching("читалку").single().pkg)
        val beforeRepeatedLookups = AppKnowledge.scanCount
        repeat(30) { assertEquals(pkg, catalog.matching("мою библиотеку").single().pkg) }
        assertEquals("Lookups rescanned the phone", beforeRepeatedLookups, AppKnowledge.scanCount)
        assertEquals("Orbit Reader", run {
            context.startActivity(Intent().setComponent(ComponentName(pkg, "$pkg.LauncherControlActivity"))
                .putExtra("mode", "catalog").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor("PACKAGE_CHANGED did not replace launcher metadata") { catalog.get(pkg)?.label == "Orbit Reader" }
            catalog.get(pkg)!!.label
        })
        assertEquals("User name was overwritten by a changed launcher", "Моя библиотека", catalog.get(pkg)!!.russian)
        catalog.resetNames(pkg)
        assertEquals("Russian Android resources were ignored", "Орбитальная читалка", catalog.get(pkg)!!.russian)
        assertEquals(pkg, catalog.matching("орбитальную читалку").single().pkg)
        catalog.saveNames(pkg, "Моя библиотека", listOf("читалка"))
        catalog.refresh()
        catalog.resetNames(pkg)
        assertEquals("Refreshing a custom name corrupted its automatic fallback", "Орбитальная читалка", catalog.get(pkg)!!.russian)
        catalog.saveNames(pkg, "Моя библиотека", listOf("читалка"))
        assertTrue(catalog.mentioned("Orbit Reader", "Открой мою библиотеку"))
        assertFalse(catalog.mentioned("Orbit Reader", "Открой другое приложение"))
        assertEquals("Orbit Reader", catalog.completeMention("читалку", "Открой читалку"))
        assertEquals("нажми Поиск", ScreenTaskProgress.remaining("открой читалку и нажми Поиск", VoiceCommand.Open("Orbit Reader"), catalog::sameApp))

        SQLiteDatabase.openDatabase(context.getDatabasePath("applications.db").path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            val plan = database.rawQuery("EXPLAIN QUERY PLAN SELECT apps.* FROM names INDEXED BY app_name_lookup JOIN apps ON apps.package=names.package WHERE names.key=? AND apps.available=1", arrayOf("читалку")).use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(3)) }
            }
            assertTrue("Alias lookup did not use the key index: $plan", plan.any { it.contains("SEARCH names") && it.contains("app_name_lookup") })
            java.io.File(context.cacheDir, "app-knowledge-query-plan.json").writeText(org.json.JSONArray(plan).toString(2))
        }
        val afterExplicitRefresh = AppKnowledge.scanCount
        assertTrue(shell("pm uninstall $pkg").contains("Success"))
        waitFor("Deleted app remained launchable") { catalog.get(pkg) == null }
        assertTrue(catalog.matching("читалку").isEmpty())
        assertEquals("User knowledge was destroyed on uninstall", "Моя библиотека", catalog.get(pkg, availableOnly = false)!!.russian)
        assertTrue(shell("pm install -r /data/local/tmp/app-knowledge-fixture.apk").contains("Success"))
        waitFor("Reinstall did not restore the knowledge binding") { catalog.matching("читалку").singleOrNull()?.pkg == pkg }
        assertEquals("Reinstall scanned all applications", afterExplicitRefresh, AppKnowledge.scanCount)
        assertEquals("Моя библиотека", catalog.get(pkg)!!.russian)

        val own = catalog.get(context.packageName)!!
        try {
            catalog.saveNames(context.packageName, own.russian, listOf("читалка"))
            assertEquals(2, catalog.matching("читалку").size)
            val tools = DeviceTools(context)
            val choice = tools.execute(VoiceCommands.parse("открой читалку"))!!
            assertTrue("A colliding nickname selected an arbitrary app", choice.appSelection)
            assertNull(choice.afterSpeech)
            assertTrue(tools.execute(VoiceCommand.Confirm)!!.text.startsWith("Сначала выберите приложение"))
            tools.clear()
        } finally { catalog.resetNames(context.packageName); catalog.resetNames(pkg) }
    }
}
