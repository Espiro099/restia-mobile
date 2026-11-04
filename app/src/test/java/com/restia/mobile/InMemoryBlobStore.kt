package com.restia.mobile

import com.restia.mobile.core.storage.BlobStore
import java.util.concurrent.ConcurrentHashMap

class InMemoryBlobStore : BlobStore {
    val data = ConcurrentHashMap<String, String>()

    override fun read(name: String): String? = data[name]

    override fun write(name: String, value: String) {
        data[name] = value
    }

    override fun delete(name: String) {
        data.remove(name)
    }

    override fun names(prefix: String) = data.keys.filter { it.startsWith(prefix) }

    override fun wipe() = data.clear()
}
