package cn.wpgd.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Fixture convention: JSON under `src/test/resources/fixtures/`, decoded strictly. */
class FixtureFormatTest {
    @Serializable
    private data class FormatSample(val schema: Int, val name: String)

    /** Strict on purpose: a fixture field the reader does not know is an error, never ignored. */
    private val strict = Json { ignoreUnknownKeys = false }

    private fun fixture(path: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$path")) { "Missing fixture: fixtures/$path" }.readText()

    @Test fun sampleFixtureLoadsFromTestResources() {
        assertEquals(FormatSample(schema = 1, name = "sample"), strict.decodeFromString<FormatSample>(fixture("format/sample.json")))
    }

    @Test fun strictParserRejectsUnknownField() {
        val withUnknown = fixture("format/sample.json").replace("\"schema\": 1,", "\"schema\": 1, \"unexpected\": true,")
        assertFailsWith<SerializationException> { strict.decodeFromString<FormatSample>(withUnknown) }
    }

    @Test fun strictParserRejectsMissingRequiredField() {
        assertFailsWith<SerializationException> { strict.decodeFromString<FormatSample>("""{"schema": 1}""") }
    }
}
