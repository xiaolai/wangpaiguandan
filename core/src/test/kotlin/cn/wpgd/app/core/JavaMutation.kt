package cn.wpgd.app.core

import java.lang.reflect.InvocationTargetException

/**
 * Calls `clear()` on [target] through `java.util.Collection` or `java.util.Map`, exactly as Java code
 * would (a Kotlin `as MutableList` cast is refused by the Kotlin runtime before reaching the object).
 * True when the object refused with `UnsupportedOperationException`; false when it was cleared.
 */
internal fun refusesJavaClear(target: Any): Boolean {
    val type = Class.forName(if (target is Map<*, *>) "java.util.Map" else "java.util.Collection")
    return try {
        type.getMethod("clear").invoke(target)
        false
    } catch (e: InvocationTargetException) {
        if (e.cause is UnsupportedOperationException) true else throw e
    }
}
