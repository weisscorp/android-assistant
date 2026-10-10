package local.voice

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

object AssetModels {
    @Synchronized fun unpack(context: Context, asset: String, name: String, version: String): File {
        val directory = File(context.filesDir, name)
        val marker = File(directory, ".complete")
        if (marker.exists() && marker.readText() == version) return directory
        directory.deleteRecursively(); check(directory.mkdirs())
        ZipInputStream(context.assets.open(asset)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.substringAfter('/')
                if (path.isEmpty()) continue
                val destination = File(directory, path)
                check(destination.canonicalPath.startsWith(directory.canonicalPath + File.separator))
                if (entry.isDirectory) destination.mkdirs() else {
                    destination.parentFile!!.mkdirs(); destination.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        marker.writeText(version)
        return directory
    }
}
