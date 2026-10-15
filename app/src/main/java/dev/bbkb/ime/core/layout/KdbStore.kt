package dev.bbkb.ime.core.layout

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The engine KDBs the app has built, kept on storage so a keyboard is built once per install, not
 * once per process: the keypad's for a device and a letter layout, the on-screen ones for a drawn
 * keyboard. Each entry is the set of files one engine variant registers.
 *
 * Entries live under `noBackupFilesDir/kdb/<install>/`, where `<install>` names the app's version
 * and install time: everything an entry was built from that ships with the app (geometry files,
 * built-in letter layouts, the build code) can only change with an install, so a new install starts
 * a new folder and the old ones are removed. What can change in between (an imported layout, the
 * drawn letters) is part of each entry's key. At most [MAX_ENTRIES] are kept, the least recently
 * used dropped first.
 *
 * Safe from several threads: an entry is written to a temporary file and renamed into place, so a
 * reader sees a whole entry or none.
 */
class KdbStore @androidx.annotation.VisibleForTesting internal constructor(private val dir: File) {

    /** The files of entry [key], or null when there is none (or it cannot be read). */
    fun get(key: String): List<ByteArray>? {
        val file = File(dir, "$key$SUFFIX")
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return null
                List(input.readInt()) { ByteArray(input.readInt()).also(input::readFully) }
            }.also { file.setLastModified(System.currentTimeMillis()) }
        } catch (e: IOException) {
            Log.w(TAG, "kdb store: cannot read ${file.name}: ${e.message}")
            file.delete()
            null
        }
    }

    /** Stores [files] as entry [key], replacing any. */
    fun put(key: String, files: List<ByteArray>) {
        try {
            dir.mkdirs()
            val tmp = File.createTempFile("kdb-$key", ".tmp", dir)
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(files.size)
                for (bytes in files) {
                    out.writeInt(bytes.size)
                    out.write(bytes)
                }
            }
            if (!tmp.renameTo(File(dir, "$key$SUFFIX"))) {
                tmp.delete()
                return
            }
            trim()
        } catch (e: IOException) {
            Log.w(TAG, "kdb store: cannot write $key: ${e.message}")
        }
    }

    /** Whether entry [key] is stored. */
    fun has(key: String): Boolean = File(dir, "$key$SUFFIX").isFile

    private fun trim() {
        val entries = dir.listFiles { f -> f.name.endsWith(SUFFIX) } ?: return
        if (entries.size <= MAX_ENTRIES) return
        entries.sortedBy { it.lastModified() }.take(entries.size - MAX_ENTRIES).forEach { it.delete() }
    }

    companion object {
        private const val TAG = "XT9KDB"
        private const val SUFFIX = ".kdbs"
        private const val MAGIC = 0x4B444231 // "KDB1"
        const val MAX_ENTRIES: Int = 96

        @Volatile
        private var instance: KdbStore? = null

        /** The app's store; removes the folders of earlier installs the first time it is asked for. */
        @JvmStatic
        fun forApp(context: Context): KdbStore = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(app: Context): KdbStore {
            val root = File(app.noBackupFilesDir, "kdb")
            val install = try {
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                @Suppress("DEPRECATION")
                "${info.versionCode}-${info.lastUpdateTime}"
            } catch (e: Exception) {
                "unknown"
            }
            root.listFiles()?.filter { it.name != install }?.forEach { it.deleteRecursively() }
            return KdbStore(File(root, install))
        }

        /** An entry key from [parts]: a short hash, safe as a file name. */
        @JvmStatic
        fun key(vararg parts: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            for (part in parts) {
                digest.update(part.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
            return digest.digest().take(16).joinToString("") { "%02x".format(it) }
        }
    }
}
