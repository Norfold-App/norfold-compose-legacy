package com.norfold.app.editor.memory

/**
 * Persistence seam for the durable Interaction Memory scopes.
 *
 * The memory runtime remains pure Kotlin/JVM. Android supplies a local backend at the UI boundary; tests can
 * use [InMemoryInteractionMemoryBackend]. Keys remain scoped by [MemoryScope], so persistence cannot turn
 * the six stores into one global preference bag.
 */
interface InteractionMemoryBackend {
    fun read(scope: MemoryScope, key: String): String?
    fun write(scope: MemoryScope, key: String, encodedValue: String)
    fun remove(scope: MemoryScope, key: String)
    fun clear(scope: MemoryScope)

    data object None : InteractionMemoryBackend {
        override fun read(scope: MemoryScope, key: String): String? = null
        override fun write(scope: MemoryScope, key: String, encodedValue: String) = Unit
        override fun remove(scope: MemoryScope, key: String) = Unit
        override fun clear(scope: MemoryScope) = Unit
    }
}

/** Deterministic test/backend implementation; the scope is part of the storage key. */
class InMemoryInteractionMemoryBackend : InteractionMemoryBackend {
    private val values = MemoryScope.entries.associateWith { linkedMapOf<String, String>() }

    override fun read(scope: MemoryScope, key: String): String? = values.getValue(scope)[key]

    override fun write(scope: MemoryScope, key: String, encodedValue: String) {
        require(scope.isDurable) { "$scope is not a durable Interaction Memory scope" }
        values.getValue(scope)[key] = encodedValue
    }

    override fun remove(scope: MemoryScope, key: String) {
        values.getValue(scope).remove(key)
    }

    override fun clear(scope: MemoryScope) {
        values.getValue(scope).clear()
    }
}

/** Stable primitive codec used by every backend. Complex tool objects remain session-local. */
internal object InteractionMemoryValueCodec {
    fun encode(value: Any): String? = when (value) {
        is String -> "s:$value"
        is Boolean -> "b:${if (value) 1 else 0}"
        is Int -> "i:$value"
        is Long -> "l:$value"
        is Float -> "f:${value.toRawBits()}"
        is Double -> "d:${value.toRawBits()}"
        else -> null
    }

    fun decode(encoded: String): Any? {
        if (encoded.length < 2 || encoded[1] != ':') return null
        val value = encoded.substring(2)
        return when (encoded[0]) {
            's' -> value
            'b' -> when (value) { "1" -> true; "0" -> false; else -> null }
            'i' -> value.toIntOrNull()
            'l' -> value.toLongOrNull()
            'f' -> value.toIntOrNull()?.let { Float.fromBits(it) }
            'd' -> value.toLongOrNull()?.let { Double.fromBits(it) }
            else -> null
        }
    }
}
