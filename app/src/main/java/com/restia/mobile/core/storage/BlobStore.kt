package com.restia.mobile.core.storage

/** Almacén local de textos: caché y borradores. La implementación real los cifra. */
interface BlobStore {
    fun read(name: String): String?
    fun write(name: String, value: String)
    fun delete(name: String)
    fun names(prefix: String): List<String>
    fun wipe()
}

/** Último contacto con el servidor, para la ventana sin conexión de 12 h (A-7). */
class ContactTracker(private val blobs: BlobStore?, private val now: () -> Long = System::currentTimeMillis) {
    @Volatile
    private var last: Long? = null

    fun record() {
        val current = now()
        val previous = lastContact()
        if (previous == null || current - previous >= WRITE_EVERY_MILLIS) blobs?.write(NAME, current.toString())
        last = current
    }

    fun windowExpired(): Boolean = lastContact()?.let { now() - it > WINDOW_MILLIS } ?: false

    private fun lastContact(): Long? = last ?: blobs?.read(NAME)?.toLongOrNull()?.also { last = it }

    private companion object {
        const val NAME = "last-contact"
        const val WRITE_EVERY_MILLIS = 60_000L
        const val WINDOW_MILLIS = 12 * 60 * 60 * 1000L
    }
}
