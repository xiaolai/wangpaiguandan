package cn.wpgd.app.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionModuleSmokeTest {
    @Test fun moduleCompilesAndRunsOnTheJvm() {
        assertEquals("session", SessionModule.NAME)
    }

    @Test fun serializationJsonIsOnTheSessionClasspath() {
        assertEquals("\"session\"", Json.encodeToString(JsonPrimitive(SessionModule.NAME)))
    }
}
