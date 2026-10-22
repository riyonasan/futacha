package com.valoser.futacha.shared.ui.ftutter

import com.valoser.futacha.shared.model.CatalogItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class FtutterOrbNgTest {
    private fun payload(count: Int = 8): ByteArray {
        val b = ByteArray(16 + count * 40)
        "ORB1".encodeToByteArray().copyInto(b)
        b[4] = 100; b[8] = 100; b[12] = count.toByte()
        return b
    }
    private fun rule(url: String = "https://img.2chan.net/b/thumb/1s.jpg", board: String = "img.2chan.net/b/") =
        FtutterOrbRule(url, board, "sample", Base64.encode(payload()))
    private fun item(url: String = rule().url) = CatalogItem("1", "https://img.2chan.net/b/res/1.htm", "sample", url, null, replyCount = 0)

    @Test fun storedFeaturesSurviveRestartAndRemoval() = runBlocking {
        var disk: String? = null
        fun store() = FtutterOrbNgStore("test", { disk }, { _, text -> disk = text })
        val first = store(); first.add(rule()); first.add(rule())
        assertEquals(listOf(rule()), store().load())
        store().remove(rule())
        assertEquals(emptyList(), store().load())
    }

    @Test fun failedSaveKeepsPreviousRegistration() = runBlocking {
        var disk: String? = null
        var fail = false
        val store = FtutterOrbNgStore("test", { disk }, { _, text -> if (fail) error("disk full") else disk = text })
        store.add(rule())
        fail = true
        assertFailsWith<IllegalStateException> { store.remove(rule()) }
        assertEquals(listOf(rule()), store.load())
        assertEquals(listOf(rule()), decodeFtutterOrbRules(requireNotNull(disk)))
    }

    @Test fun recreatedCatalogWaitsForCancelledSaveAndKeepsAllRegistrations() = runBlocking {
        var disk: String? = null
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var holdWrites = false
        var created = 0
        val path = "recreated-${kotlin.random.Random.nextLong()}"
        val create: (String) -> FtutterOrbNgStore = { name ->
            created++
            FtutterOrbNgStore(name, { disk }, { _, text ->
                if (holdWrites) { started.complete(Unit); finish.await() }
                disk = text
            })
        }
        val a = rule("https://example.test/a"); val b = rule("https://example.test/b"); val c = rule("https://example.test/c")
        val store = ftutterOrbStore(path, create)
        store.add(a)
        holdWrites = true
        val job = launch { store.add(b) }
        started.await()
        job.cancel()
        val recreated = ftutterOrbStore(path, create)
        val reloaded = async(start = CoroutineStart.UNDISPATCHED) { recreated.load() }
        assertFalse(reloaded.isCompleted)
        finish.complete(Unit)
        job.join()
        assertEquals(listOf(a, b), reloaded.await())
        holdWrites = false
        recreated.add(c)
        assertEquals(1, created)
        assertEquals(listOf(a, b, c), recreated.load())
        assertEquals(listOf(a, b, c), decodeFtutterOrbRules(requireNotNull(disk)))
    }

    @Test fun unreadableRegistrationsAreNeverSilentlyReplaced() = runBlocking {
        var disk = "corrupt"
        val store = FtutterOrbNgStore("test", { disk }, { _, text -> disk = text })
        assertFails { store.add(rule()) }
        assertEquals("corrupt", disk)
    }

    @Test fun corruptAndOversizedFeaturesAreRejected() {
        assertFails { decodeFtutterOrbRules("broken") }
        assertFails { ftutterOrbFeatureCount(payload().dropLast(1).toByteArray()) }
        val bad = payload(); bad[12] = -1
        assertFails { ftutterOrbFeatureCount(bad) }
        val nan = payload(); nan[18] = -64; nan[19] = 127
        assertFails { ftutterOrbFeatureCount(nan) }
        assertEquals(0, ftutterOrbFeatureCount(payload(0)))
    }

    @Test fun scopeAndRuleChangesNeverExposeUncheckedImages() {
        val state = FtutterOrbCatalogState("img.2chan.net/b/", true)
        val candidate = item()
        assertNull(state.verdict(candidate))
        state.loadError = "unreadable"
        assertEquals(FtutterOrbVerdict.ERROR, state.verdict(candidate))
        state.loadError = null
        state.rules = listOf(rule(board = "other"))
        assertEquals(FtutterOrbVerdict.CLEAR, state.verdict(candidate))
        state.rules = listOf(rule())
        assertNull(state.verdict(candidate))
        state.checkedRules = state.rules
        val candidateUrl = requireNotNull(state.url(candidate))
        state.verdicts = mapOf(candidateUrl to FtutterOrbVerdict.CLEAR)
        assertEquals(FtutterOrbVerdict.CLEAR, state.verdict(candidate))
        state.rules = state.rules!! + rule("https://img.2chan.net/b/thumb/2s.jpg", "*")
        assertNull(state.verdict(candidate))
        state.checkedRules = state.rules
        state.verdicts = mapOf(candidateUrl to FtutterOrbVerdict.HIDDEN)
        assertEquals(FtutterOrbVerdict.HIDDEN, state.verdict(candidate))
        assertNull(state.verdict(item("https://img.2chan.net/b/thumb/3s.jpg")))
        assertEquals(2, state.active.size)
        assertEquals(FtutterOrbVerdict.CLEAR, FtutterOrbCatalogState("board", false).verdict(candidate))
    }

    @Test fun usesThumbAndRespectsHttpsPreference() {
        val state = FtutterOrbCatalogState("img.2chan.net/b/", true, true)
        assertEquals("https://img.2chan.net/b/thumb/1s.jpg", state.url(item("http://img.2chan.net/b/cat/1s.jpg")))
    }

    @Test fun httpFailuresRemainHiddenAndSuccessfulFeaturesAreCached() = runBlocking {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            if (request.url.encodedPath.endsWith("bad")) respond("", HttpStatusCode.NotFound) else respond(byteArrayOf(1))
        }
        val client = HttpClient(engine)
        try {
            val cache = FtutterOrbCache { payload() }
            val results = mutableMapOf<String, FtutterOrbVerdict>()
            val urls = listOf(rule().url, "https://example.test/good", "https://example.test/bad")
            ftutterCheckOrb(urls, listOf(rule()), cache, client, { url, v -> results[url] = v }, match = { _, _ -> false })
            assertEquals(FtutterOrbVerdict.HIDDEN, results[rule().url])
            assertEquals(FtutterOrbVerdict.CLEAR, results[urls[1]])
            assertEquals(FtutterOrbVerdict.ERROR, results[urls[2]])
            assertEquals(2, requests)
            cache.get(client, urls[1])
            assertEquals(2, requests)
        } finally { client.close() }
    }

    @Test fun cancellationStopsScanWithoutPublishingClear() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val engine = MockEngine { started.complete(Unit); awaitCancellation() }
        val client = HttpClient(engine)
        try {
            val results = mutableListOf<FtutterOrbVerdict>()
            val job = launch { ftutterCheckOrb(listOf("https://example.test/wait"), listOf(rule()), FtutterOrbCache { payload() }, client,
                { _, verdict -> results += verdict }) }
            started.await()
            job.cancelAndJoin()
            assertTrue(results.isEmpty())
        } finally { client.close() }
    }
}
