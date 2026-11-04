package com.restia.mobile.core.security

import android.content.Context
import android.util.AtomicFile
import com.restia.mobile.core.storage.BlobStore
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/** Un archivo cifrado por entrada, fuera de los respaldos de Android. */
class EncryptedFileStore(context: Context) : BlobStore {
    private val dir = File(context.noBackupFilesDir, "secure").apply { mkdirs() }

    private fun file(name: String) = AtomicFile(File(dir, name.replace(UNSAFE, "_")))

    override fun read(name: String): String? = try {
        String(KeystoreCipher.decrypt(file(name).readFully()))
    } catch (_: IOException) {
        null
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    @Synchronized
    override fun write(name: String, value: String) {
        val target = file(name)
        val stream = target.startWrite()
        try {
            stream.write(KeystoreCipher.encrypt(value.toByteArray()))
            target.finishWrite(stream)
        } catch (e: IOException) {
            target.failWrite(stream)
            throw e
        }
    }

    @Synchronized
    override fun delete(name: String) {
        file(name).delete()
    }

    override fun names(prefix: String): List<String> =
        dir.list().orEmpty().filter { it.startsWith(prefix) && !it.endsWith(".new") && !it.endsWith(".bak") }

    @Synchronized
    override fun wipe() {
        dir.listFiles().orEmpty().forEach { it.delete() }
    }

    private companion object {
        val UNSAFE = Regex("[^A-Za-z0-9_-]")
    }
}
