package cn.wpgd.app.core

/**
 * Read-only snapshots that no caller can change, not even through a cast to a mutable type or from
 * Java (whose `clear`, `add`, `put` and `remove` throw `UnsupportedOperationException` on them).
 * A Kotlin `toList()` or `toMap()` result is a `java.util.ArrayList` or `LinkedHashMap` underneath,
 * so it is no snapshot. Stdlib only: `:core` main code takes no `java.*` dependency.
 */
internal fun <T> List<T>.readOnlyCopy(): List<T> = this as? ReadOnlyList<T> ?: ReadOnlyList(toList())

internal fun <K, V> Map<K, V>.readOnlyCopy(): Map<K, V> = this as? ReadOnlyMap<K, V> ?: ReadOnlyMap(entries.map { Entry(it.key, it.value) })

private class ReadOnlyList<T>(private val items: List<T>) : AbstractList<T>() {
    override val size: Int get() = items.size
    override fun get(index: Int): T = items[index]
}

private class Entry<K, V>(override val key: K, override val value: V) : Map.Entry<K, V> {
    override fun equals(other: Any?): Boolean = other is Map.Entry<*, *> && other.key == key && other.value == value
    override fun hashCode(): Int = key.hashCode() xor value.hashCode()
    override fun toString(): String = "$key=$value"
}

/** Keeps the source's iteration order. */
private class ReadOnlyMap<K, V>(items: List<Entry<K, V>>) : AbstractMap<K, V>() {
    private val list = ReadOnlyList(items)
    private val index: Map<K, V> = items.associate { it.key to it.value } // private: never handed out
    override val entries: Set<Map.Entry<K, V>> = object : AbstractSet<Map.Entry<K, V>>() {
        override val size: Int get() = list.size
        override fun iterator(): Iterator<Map.Entry<K, V>> = list.iterator()
    }
    override fun get(key: K): V? = index[key]
    override fun containsKey(key: K): Boolean = key in index
}
