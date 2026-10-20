package com.befeast.dremla.resolver

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class ContractTest(private val path: File) {
    @Test fun contractVector() {
        when {
            path.parentFile.name == "invalid" -> assertThrows(Exception::class.java) { Catalog.parse(path.readText()) }
            path.name.startsWith("catalog-") -> {
                val catalog = Catalog.parse(path.readText())
                val expected = JSONObject(File(path.parentFile, "selection-v${catalog.version}.json").readText())
                assertEquals(expected.getLong("catalogVersion"), catalog.version)
                val ids = expected.getJSONArray("selection")
                assertEquals((0 until ids.length()).map(ids::getString), catalog.selection)
            }
            else -> {
                val vector = JSONObject(path.readText())
                val ids = vector.getJSONArray("selection")
                val night = OnlineNight((0 until ids.length()).map(ids::getString))
                val steps = vector.getJSONArray("steps")
                for (index in 0 until steps.length()) {
                    val step = steps.getJSONObject(index)
                    night.apply(step.getString("event"), step.optString("track").ifEmpty { null },
                        step.optString("result").ifEmpty { null })
                    val expected = step.getJSONObject("expect")
                    val label = "${path.name}, step ${index + 1}"
                    assertEquals(label, expected.getString("phase"), night.phase)
                    assertEquals(label, expected.getString("timer"), night.timer)
                    assertEquals(label, expected.getBoolean("sound"), night.sound)
                    if (step.has("result") && step.getString("result") != "ok") assertFalse(label, night.sound)
                }
            }
        }
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun vectors(): List<Array<File>> {
            val root = File(System.getProperty("fixtures"))
            val files = root.walkTopDown().filter { it.isFile && it.extension == "json" &&
                (it.parentFile.name == "invalid" || it.name.startsWith("catalog-v") || it.name.startsWith("playback-"))
            }.sortedBy { it.path }.toList()
            check(files.size >= 19) { "Contract fixtures missing" }
            return files.map { arrayOf(it) }
        }
    }
}

class NightIntegrationTest {
    @Test fun fixtureFailuresNeverPlayAndRecoveryDoesNotRestart() {
        for (reason in FailureReason.entries) {
            val night = OnlineNight(listOf("yt-fxFail00001"))
            val resolver = FixtureResolver(mapOf("fxFail00001" to Failure(reason)))
            night.resolveCurrent(resolver)
            assertFalse(night.sound)
            if (reason == FailureReason.NETWORK) night.resolveCurrent(resolver)
            assertEquals("unavailable", night.phase)
            assertEquals(reason, night.lastFailure)
            assertEquals("running", night.timer)
            night.apply("networkRecovered")
            assertEquals("unavailable", night.phase)
            night.apply("timerExpired")
            night.apply("networkRecovered")
            assertEquals("finished", night.phase)
            assertEquals("expired", night.timer)
        }
    }

    @Test fun fixtureSuccessAndResumeKeepPositionAndShareBudget() {
        val night = OnlineNight(listOf("yt-fxAmbient01"))
        night.resolveCurrent(FixtureResolver(mapOf("fxAmbient01" to Failure(FailureReason.NETWORK))))
        assertEquals(2, night.budget)
        night.resolveCurrent(FixtureResolver(mapOf("fxAmbient01" to Resolved("https://media.example/audio", "audio/mp4"))))
        assertTrue(night.sound)
        night.apply("drop", positionMs = 42_000)
        assertEquals(42_000L, night.resumePositionMs)
        assertEquals(1, night.budget)
        night.apply("resume", result = "ok")
        night.apply("drop", positionMs = 43_000)
        assertEquals("unavailable", night.phase)
        assertEquals(1, night.streak)
    }

    @Test fun trackEndResetsStreakAndPerTrackAllowancesButNotNightBudget() {
        val night = OnlineNight(listOf("a", "b"))
        night.apply("resolve", "a", "unavailable")
        night.apply("resolve", "b", "ok")
        night.apply("drop", positionMs = 500)
        night.apply("resume", result = "ok")
        night.apply("trackEnd")
        assertEquals(0, night.streak)
        assertEquals(2, night.budget)
        night.apply("resolve", "a", "network")
        night.apply("resolve", "a", "ok")
        night.apply("drop", positionMs = 900)
        assertEquals("resuming", night.phase)
        assertEquals(0, night.budget)
    }

    @Test fun parserRejectsDuplicateFieldsAndWrongTypes() {
        val valid = File(System.getProperty("fixtures"), "catalog-v1.json").readText()
        assertThrows(Exception::class.java) { Catalog.parse(valid.replace("\"version\": 1", "\"version\": 1, \"version\": 2")) }
        assertThrows(Exception::class.java) { Catalog.parse(valid.replace("\"version\": 1", "\"version\": \"1\"")) }
        assertThrows(Exception::class.java) { Catalog.parse(valid + " false") }
    }
}
