package app.filesend.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Vecteurs partages avec Rust (protocol-tests/vectors.json). */
class VectorsTest {
    private val v: JsonObject = Json.parseToJsonElement(
        File(System.getProperty("protocolTests"), "vectors.json").readText(),
    ).jsonObject

    private fun generated(o: JsonObject): String =
        List(o.getValue("times").jsonPrimitive.int) { o.getValue("unit").jsonPrimitive.content }
            .joinToString(o.getValue("join").jsonPrimitive.content)

    @Test fun validPaths() {
        for (c in v.getValue("pathsValid").jsonArray) {
            val o = c.jsonObject
            val input = o.getValue("input").jsonPrimitive.content
            val want = o.getValue("components").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(input, want, sanitizeRelativePath(input))
        }
        for (g in v.getValue("pathsValidGenerated").jsonArray) sanitizeRelativePath(generated(g.jsonObject))
    }

    @Test fun invalidPaths() {
        for (s in v.getValue("pathsInvalid").jsonArray) {
            val input = s.jsonPrimitive.content
            try {
                sanitizeRelativePath(input)
                fail("devrait refuser '$input'")
            } catch (_: PathRefused) {
            }
        }
        for (g in v.getValue("pathsInvalidGenerated").jsonArray) {
            try {
                sanitizeRelativePath(generated(g.jsonObject))
                fail(g.jsonObject.getValue("why").jsonPrimitive.content)
            } catch (_: PathRefused) {
            }
        }
    }

    @Test fun collisions() {
        for (c in v.getValue("collisions").jsonArray) {
            val o = c.jsonObject
            val existing = o.getValue("existing").jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertEquals(
                o.toString(),
                o.getValue("expected").jsonPrimitive.content,
                uniqueName(o.getValue("name").jsonPrimitive.content) { it in existing },
            )
        }
    }

    @Test fun prepareUploadJson() {
        val p = v.getValue("prepareUpload").jsonObject
        val req = ProtocolJson.decodeFromString<PrepareUploadRequest>(p.getValue("request").toString())
        assertEquals("Mon PC", req.info.alias)
        assertEquals("photos/a.jpg", req.files.getValue("f0").relativePath)
        assertEquals("00ff", req.files.getValue("f0").sha256)
        assertNull(req.files.getValue("f1").sha256)
        val resp = ProtocolJson.decodeFromString<PrepareUploadResponse>(p.getValue("response").toString())
        assertEquals("tok-1", resp.files.getValue("f1"))
        // sha256 absent n'est pas ecrit (comme cote Rust).
        val out = ProtocolJson.encodeToString(PrepareUploadRequest.serializer(), req)
        assertTrue(out, !out.contains("null"))
    }

    @Test fun failReasonTexts() {
        val want = (v.getValue("failReasons") as JsonArray).map { it.jsonPrimitive.content }
        assertEquals(want, FailReason.entries.map { it.message })
    }

    @Test fun messageTexts() {
        val m = v.getValue("messages").jsonObject
        for (c in m.getValue("formatSize").jsonArray) {
            val o = c.jsonObject
            assertEquals(o.toString(), o.getValue("text").jsonPrimitive.content, Messages.formatSize(o.getValue("bytes").jsonPrimitive.content.toLong()))
        }
        for (c in m.getValue("request").jsonArray) {
            val o = c.jsonObject
            val (t, d) = Messages.request(
                o.getValue("alias").jsonPrimitive.content, o.getValue("count").jsonPrimitive.int,
                o.getValue("total").jsonPrimitive.content.toLong(),
            )
            assertEquals(o.getValue("title").jsonPrimitive.content, t)
            assertEquals(o.getValue("detail").jsonPrimitive.content, d)
        }
        val s = m.getValue("status").jsonObject
        val a = s.getValue("alias").jsonPrimitive.content
        assertEquals(s.getValue("sending").jsonPrimitive.content, Messages.sending(a))
        assertEquals(s.getValue("sent").jsonPrimitive.content, Messages.sent(a))
        assertEquals(s.getValue("receiving").jsonPrimitive.content, Messages.receiving(a))
        assertEquals(s.getValue("received").jsonPrimitive.content, Messages.received(a))
        for (c in m.getValue("percent").jsonArray) {
            val o = c.jsonObject
            assertEquals(
                o.getValue("percent").jsonPrimitive.int,
                Messages.percent(o.getValue("done").jsonPrimitive.content.toLong(), o.getValue("total").jsonPrimitive.content.toLong()),
            )
        }
    }
}
