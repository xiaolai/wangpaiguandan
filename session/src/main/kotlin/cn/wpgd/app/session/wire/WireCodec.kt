package cn.wpgd.app.session.wire

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Strict JSON for the wire. Decoding rejects unknown fields, missing fields, wrong types and input
 * nested deeper than [MAX_DEPTH] (checked before the recursive parser runs). Encoding writes every
 * field, nulls included, in declaration order, so equal messages give equal bytes.
 */
object WireCodec {
    const val MAX_DEPTH = 8

    val json: Json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
        prettyPrint = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    fun encode(message: WireMessage): ByteArray = encodeToString(message).encodeToByteArray()

    fun encodeToString(message: WireMessage): String = json.encodeToString(WireMessage.serializer(), message)

    /** The message, or null for anything malformed (including a value of the wrong JSON type). Never throws on input. */
    fun decode(bytes: ByteArray): WireMessage? {
        // Strict UTF-8: malformed bytes are refused, while a literal U+FFFD (valid UTF-8) is ordinary text.
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return null
        }
        return decode(text)
    }

    fun decode(text: String): WireMessage? {
        if (depth(text) > MAX_DEPTH) return null
        return try {
            val element = json.parseToJsonElement(text)
            if (!StrictTypes.ok(element, WireMessage.serializer().descriptor)) return null
            json.decodeFromJsonElement(WireMessage.serializer(), element)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Maximum bracket depth outside string literals; [Int.MAX_VALUE] when a closing bracket has no opener. */
    internal fun depth(text: String): Int {
        var depth = 0
        var max = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> { depth++; if (depth > max) max = depth }
                '}', ']' -> { depth--; if (depth < 0) return Int.MAX_VALUE }
            }
            i++
        }
        return max
    }
}

/** base64url without padding, the encoding of every binary field on the wire. */
@OptIn(ExperimentalEncodingApi::class)
object B64 {
    private val codec = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    fun encode(bytes: ByteArray): String = codec.encode(bytes)

    /** Null when [text] is not valid base64url. */
    fun decode(text: String): ByteArray? = try {
        codec.decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }
}

object Hex {
    fun encode(bytes: ByteArray): String = bytes.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }

    fun decode(text: String): ByteArray {
        require(text.length % 2 == 0) { "odd hex length" }
        return ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
