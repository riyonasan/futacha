package com.valoser.futacha.shared.ui.ftutter

import com.valoser.futacha.shared.model.CatalogItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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

    @Test fun downloadedImagesAreReusedWithinABoundedLruCache() = runBlocking {
        var requests = 0
        val body = ByteArray(2 * 1024 * 1024) { 7 }
        val client = HttpClient(MockEngine { requests++; respond(body) })
        try {
            val cache = FtutterOrbCache { payload() }
            val urls = (0..4).map { "https://example.test/$it" }
            for (url in urls.take(4)) cache.get(client, url)
            assertContentEquals(body, cache.image(urls[0]))
            cache.get(client, urls[4])
            assertNull(cache.image(urls[1]))
            assertNotNull(cache.image(urls[0]))
            assertNotNull(cache.image(urls[4]))
            cache.get(client, urls[4])
            assertEquals(5, requests)
            val invalid = FtutterOrbCache { byteArrayOf() }
            assertFails { invalid.get(client, urls[0]) }
            assertNull(invalid.image(urls[0]))
        } finally { client.close() }
    }

    @Test fun scrollingChangesPriorityWithoutCancellingActiveRequests() = runBlocking {
        val urls = (0..8).map { "https://example.test/$it" }
        val permits = List(3) { CompletableDeferred<Unit>() }
        val initialStarted = CompletableDeferred<Unit>()
        val preferredStarted = CompletableDeferred<Unit>()
        val order = mutableListOf<Int>()
        val orderLock = Mutex()
        var preferred = emptySet<String>()
        val client = HttpClient(MockEngine { request ->
            val id = request.url.encodedPath.substringAfterLast('/').toInt()
            if (orderLock.withLock { order += id; order.size } == 3) initialStarted.complete(Unit)
            if (id < 3) permits[id].await()
            if (id == 8) preferredStarted.complete(Unit)
            respond(byteArrayOf(1))
        })
        val job = launch {
            ftutterCheckOrb(urls, listOf(rule()), FtutterOrbCache { payload() }, client,
                { _, _ -> }, match = { _, _ -> false }, preferredUrls = { preferred })
        }
        try {
            withTimeout(2_000) { initialStarted.await() }
            preferred = setOf(urls[8])
            permits[1].complete(Unit)
            withTimeout(2_000) { preferredStarted.await() }
            assertEquals(setOf(0, 1, 2), orderLock.withLock { order.take(3).toSet() })
            assertEquals(8, orderLock.withLock { order[3] })
        } finally {
            permits.forEach { it.complete(Unit) }
            job.cancelAndJoin()
            client.close()
        }
    }

    @Test fun oneSlowImageDoesNotBlockEveryThirdImage() = runBlocking {
        val wait = CompletableDeferred<Unit>()
        val othersFinished = CompletableDeferred<Unit>()
        val results = mutableSetOf<String>()
        val urls = (0..8).map { "https://example.test/$it" }
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/0") wait.await()
            respond(byteArrayOf(1))
        })
        val job = launch {
            ftutterCheckOrb(urls, listOf(rule()), FtutterOrbCache { payload() }, client,
                { url, verdict ->
                    assertEquals(FtutterOrbVerdict.CLEAR, verdict)
                    results += url
                    if (results.size == 8) othersFinished.complete(Unit)
                }, match = { _, _ -> false })
        }
        try {
            withTimeout(2_000) { othersFinished.await() }
            assertFalse(urls[0] in results)
            assertEquals(urls.drop(1).toSet(), results)
        } finally {
            wait.complete(Unit)
            job.cancelAndJoin()
            client.close()
        }
    }

    @Test fun reopeningAndReorderingWholeCatalogDoesNotFetchExtractOrMatchAgain() = runBlocking {
        val requests = MutableStateFlow(0)
        val extractions = MutableStateFlow(0)
        val matches = MutableStateFlow(0)
        val client = HttpClient(MockEngine { requests.update { it + 1 }; respond(byteArrayOf(1)) })
        val cache = FtutterOrbCache { extractions.update { it + 1 }; payload() }
        val rules = listOf(rule())
        val urls = (0 until 300).map { "https://example.test/$it" }
        fun screen() = FtutterOrbCatalogState("img.2chan.net/b/", true).apply {
            this.rules = rules; checkedRules = rules
        }
        suspend fun scan(state: FtutterOrbCatalogState, order: List<String>) {
            ftutterCheckOrb(order, rules, cache, client,
                { url, verdict -> state.verdicts = state.verdicts + (url to verdict) },
                match = { _, _ -> matches.update { it + 1 }; false })
        }
        try {
            scan(screen(), urls)
            val reopened = screen()
            scan(reopened, urls.asReversed())
            scan(screen(), urls)
            assertEquals(300, requests.value)
            assertEquals(300, extractions.value)
            assertEquals(300, matches.value)
            assertEquals(FtutterOrbVerdict.CLEAR, reopened.verdict(item(urls.first())))
            assertEquals(FtutterOrbVerdict.CLEAR, reopened.verdict(item(urls.last())))
        } finally { client.close() }
    }

    @Test fun verdictsAreInvalidatedByExactApplicableRulesAndTransport() = runBlocking {
        val client = HttpClient(MockEngine { respond(byteArrayOf(1)) })
        val cache = FtutterOrbCache { payload() }
        val url = "https://example.test/image"
        val first = listOf(rule())
        val changed = first + rule("https://example.test/new-reference")
        var matches = 0
        try {
            ftutterCheckOrb(listOf(url, rule().url), first, cache, client, { _, _ -> },
                match = { _, _ -> matches++; false })
            assertEquals(FtutterOrbVerdict.CLEAR, cache.verdict(url, first))
            assertEquals(FtutterOrbVerdict.HIDDEN, cache.verdict(rule().url, first))
            assertNull(cache.verdict(url, changed))
            assertNull(cache.verdict(url, listOf(rule(board = "other"))))
            assertNull(cache.verdict(url, listOf(rule().copy(features = Base64.encode(payload(9))))))
            assertNull(cache.verdict(url.replace("https://", "http://"), first))
            ftutterCheckOrb(listOf(url), changed, cache, client, { _, _ -> },
                match = { _, _ -> matches++; true })
            assertEquals(2, matches)
            assertEquals(FtutterOrbVerdict.HIDDEN, cache.verdict(url, changed))
            assertNull(cache.verdict(url, first))
        } finally { client.close() }
    }

    @Test fun completedVerdictsSurviveLeavingBeforeScanFinishes() = runBlocking {
        val requests = MutableStateFlow(0)
        val completed = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { request ->
            requests.update { it + 1 }
            if (request.url.encodedPath == "/wait") awaitCancellation()
            respond(byteArrayOf(1))
        })
        val cache = FtutterOrbCache { payload() }
        val rules = listOf(rule())
        val url = "https://example.test/ready"
        val job = launch {
            ftutterCheckOrb(listOf(url, "https://example.test/wait"), rules, cache, client,
                { name, _ -> if (name == url) completed.complete(Unit) }, match = { _, _ -> false })
        }
        try {
            withTimeout(2_000) { completed.await() }
            job.cancelAndJoin()
            val before = requests.value
            ftutterCheckOrb(listOf(url), rules, cache, client, { _, _ -> },
                match = { _, _ -> error("Completed result should survive leaving") })
            assertEquals(before, requests.value)
            assertEquals(FtutterOrbVerdict.CLEAR, cache.verdict(url, rules))
        } finally { job.cancelAndJoin(); client.close() }
    }

    @Test fun failedVerdictsAreRetriedOnNextVisit() = runBlocking {
        var requests = 0
        var fail = true
        val client = HttpClient(MockEngine {
            requests++
            if (fail) respond("", HttpStatusCode.NotFound) else respond(byteArrayOf(1))
        })
        val cache = FtutterOrbCache { payload() }
        val rules = listOf(rule())
        val url = "https://example.test/retry"
        try {
            ftutterCheckOrb(listOf(url), rules, cache, client, { _, _ -> }, match = { _, _ -> false })
            assertNull(cache.verdict(url, rules))
            fail = false
            ftutterCheckOrb(listOf(url), rules, cache, client, { _, _ -> }, match = { _, _ -> false })
            assertEquals(2, requests)
            assertEquals(FtutterOrbVerdict.CLEAR, cache.verdict(url, rules))
        } finally { client.close() }
    }

    @Test fun verdictCacheIsBoundedAndRetainsRecentlyUsedResults() {
        val cache = FtutterOrbCache { payload() }
        val rules = listOf(rule())
        for (i in 0 until 4096) cache.rememberVerdict("https://example.test/$i", rules, FtutterOrbVerdict.CLEAR)
        assertNotNull(cache.verdict("https://example.test/0", rules))
        cache.rememberVerdict("https://example.test/4096", rules, FtutterOrbVerdict.HIDDEN)
        assertNull(cache.verdict("https://example.test/1", rules))
        assertEquals(FtutterOrbVerdict.CLEAR, cache.verdict("https://example.test/0", rules))
        assertEquals(FtutterOrbVerdict.HIDDEN, cache.verdict("https://example.test/4096", rules))
    }
}
