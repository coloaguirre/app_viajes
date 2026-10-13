package com.bitacoraviaje.core.export

import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TripJsonWriterTest {
    private val schemaText = javaClass.getResourceAsStream("/trip.schema.json")!!.readBytes().toString(Charsets.UTF_8)

    @Test
    fun `output validates against docs trip schema json`() {
        val schema = JsonSchema.fromDefinition(schemaText)
        val doc = Json.parseToJsonElement(Fixture.render(TripJsonWriter()))
        val errors = ArrayList<ValidationError>()
        val ok = schema.validate(doc, errors::add)
        assertTrue(ok, "schema errors: " + errors.take(10).joinToString("\n") { "${it.objectPath}: ${it.message}" })
    }

    @Test
    fun `local times carry the segment offset and media reference their zip paths`() {
        val doc: JsonElement = Json.parseToJsonElement(Fixture.render(TripJsonWriter()))
        val seg = doc.jsonObject["segments"]!!.jsonArray[0].jsonObject
        assertEquals("2025-10-09T05:53:20-03:00", seg["started"]!!.jsonPrimitive.content)
        assertEquals(Fixture.T0, seg["started_utc_ms"]!!.jsonPrimitive.content.toLong())
        assertEquals(361 - 7, seg["points"]!!.jsonArray.size)
        val media = seg["media"]!!.jsonArray
        assertEquals("media/fotos/2025-10-09/IMG_0001.jpg", media[0].jsonObject["file"]!!.jsonPrimitive.content)
        assertEquals(Fixture.TRICKY, media[0].jsonObject["caption"]!!.jsonPrimitive.content)
        assertEquals(1, doc.jsonObject["schema_version"]!!.jsonPrimitive.content.toInt())
    }
}
