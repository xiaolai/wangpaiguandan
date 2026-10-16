package cn.wpgd.app.session.wire

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON type check against a serial descriptor, run before decoding. kotlinx-serialization accepts a
 * quoted number (`"3"`) for an integer field even when not lenient; a Swift `Codable` decoder does
 * not. Refusing it here keeps both platforms' acceptance identical. Unknown and missing fields are
 * left to the strict decoder.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object StrictTypes {
    private val INTEGER = Regex("-?(0|[1-9][0-9]*)")

    fun ok(e: JsonElement, d: SerialDescriptor): Boolean {
        if (e is JsonNull) return d.isNullable
        return when (val kind = d.kind) {
            PolymorphicKind.SEALED -> {
                val obj = e as? JsonObject ?: return false
                val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
                val sub = d.getElementDescriptor(1).elementDescriptors.firstOrNull { it.serialName == type } ?: return false
                fields(JsonObject(obj - "type"), sub)
            }
            StructureKind.CLASS, StructureKind.OBJECT -> e is JsonObject && fields(e, d)
            StructureKind.LIST -> e is JsonArray && e.all { ok(it, d.getElementDescriptor(0)) }
            SerialKind.ENUM -> e is JsonPrimitive && e.isString
            PrimitiveKind.STRING -> e is JsonPrimitive && e.isString
            PrimitiveKind.BOOLEAN -> e is JsonPrimitive && !e.isString && (e.content == "true" || e.content == "false")
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE ->
                e is JsonPrimitive && !e.isString && INTEGER.matches(e.content)
            else -> kind !is PrimitiveKind // floats and other kinds are not used on the wire
        }
    }

    private fun fields(obj: JsonObject, d: SerialDescriptor): Boolean = obj.all { (key, value) ->
        val i = d.getElementIndex(key)
        i < 0 || ok(value, d.getElementDescriptor(i))
    }
}
