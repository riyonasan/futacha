package com.valoser.futacha.shared.ui.ftutter

import com.valoser.futacha.shared.model.CatalogItem
import com.valoser.futacha.shared.network.readBoundedHttpResponseBytes
import com.valoser.futacha.shared.util.AppDispatchers
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

internal fun ftutterOrbThumbnail(item: CatalogItem): String? = item.thumbnailUrl
    ?.takeIf { it.isNotBlank() }?.replace("/cat/", "/thumb/")

@Serializable
internal data class FtutterOrbRule(val url: String, val board: String, val memo: String, val features: String)

internal enum class FtutterOrbVerdict { CLEAR, HIDDEN, ERROR }

// UI-thread owned; the mutex/cache must outlive a catalog host while its cancelled save finishes.
private val ftutterOrbStores = mutableMapOf<String, FtutterOrbNgStore>()
internal fun ftutterOrbStore(path: String, create: (String) -> FtutterOrbNgStore = ::FtutterOrbNgStore): FtutterOrbNgStore =
    ftutterOrbStores.getOrPut(path) { create(path) }

/** Separate from pHash: its distance slider and the other modes must not reinterpret ORB payloads. */
internal class FtutterOrbNgStore(
    private val path: String,
    private val read: suspend (String) -> String? = ::ftutterReadOrbFile,
    private val write: suspend (String, String) -> Unit = ::ftutterWriteOrbFile
) {
    private val mutex = Mutex()
    private var loaded: List<FtutterOrbRule>? = null
    private suspend fun readRules(): List<FtutterOrbRule> = read(path)?.let { text ->
        withContext(AppDispatchers.parsing) { decodeFtutterOrbRules(text) }
    }.orEmpty()

    suspend fun load(): List<FtutterOrbRule> = mutex.withLock {
        loaded ?: readRules().also { loaded = it }
    }

    suspend fun add(rule: FtutterOrbRule): List<FtutterOrbRule> = edit { old ->
        (old.filterNot { it.url == rule.url && it.board == rule.board } + rule)
    }

    suspend fun remove(rule: FtutterOrbRule): List<FtutterOrbRule> = edit { it - rule }

    private suspend fun edit(change: (List<FtutterOrbRule>) -> List<FtutterOrbRule>): List<FtutterOrbRule> = mutex.withLock {
        val old = loaded ?: readRules()
        val next = change(old)
        require(next.size <= 64) { "ORB画像NGは64枚まで登録できます。不要な登録を削除してください" }
        val payload = withContext(AppDispatchers.parsing) {
            next.forEach(::validateFtutterOrbRule)
            Json.encodeToString(next)
        }
        withContext(NonCancellable) {
            write(path, payload)
            // Publish only after the atomic write succeeded; failures retain the previous rules.
            next.also { loaded = it }
        }
    }
}

internal fun decodeFtutterOrbRules(text: String): List<FtutterOrbRule> {
    require(text.length <= 8 * 1024 * 1024) { "画像NGの保存データが大きすぎます" }
    return Json.decodeFromString<List<FtutterOrbRule>>(text).also { rules ->
        require(rules.size <= 64) { "画像NGの登録数が上限を超えています" }
        rules.forEach(::validateFtutterOrbRule)
    }
}

private fun validateFtutterOrbRule(rule: FtutterOrbRule) {
    require(rule.url.length in 1..8192 && rule.board.length in 1..300 && rule.memo.length <= 300 &&
        rule.features.length <= 53356) { "画像NGの保存データが不正です" }
    val bytes = Base64.decode(rule.features)
    require(ftutterOrbFeatureCount(bytes) >= 8) { "画像NGの特徴量が不正です" }
}

internal fun ftutterOrbFeatureCount(bytes: ByteArray): Int {
    require(bytes.size in 16..40016 && bytes.copyOfRange(0, 4).decodeToString() == "ORB1") { "ORBの形式が不正です" }
    fun intAt(offset: Int) = (0..3).fold(0) { n, i -> n or ((bytes[offset + i].toInt() and 255) shl (8 * i)) }
    val width = intAt(4); val height = intAt(8); val count = intAt(12)
    require(width > 0 && height > 0 && width.toLong() * height <= 4_194_304 &&
        count in 0..1000 && bytes.size == 16 + count * 40) { "ORBの特徴量が不正です" }
    repeat(count) { i ->
        val x = Float.fromBits(intAt(16 + i * 40)); val y = Float.fromBits(intAt(20 + i * 40))
        require(x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < width && y < height) { "ORBの座標が不正です" }
    }
    return count
}

/** Bounded per-screen feature cache. Rules persist, candidates can always be downloaded again. */
internal class FtutterOrbCache(private val extract: suspend (ByteArray) -> ByteArray = ::ftutterExtractOrb) {
    private val features = LinkedHashMap<String, ByteArray>()
    suspend fun get(client: HttpClient, url: String): ByteArray {
        features[url]?.let { return it }
        val bytes = withTimeout(5_000) {
            client.prepareGet(url).execute { response ->
                check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
                readBoundedHttpResponseBytes(response, 2 * 1024 * 1024, 5_000)
            }
        }
        val result = extract(bytes)
        ftutterOrbFeatureCount(result)
        features[url] = result
        while (features.size > 256) features.remove(features.keys.first())
        return result
    }
}

internal suspend fun ftutterCheckOrb(
    urls: List<String>, rules: List<FtutterOrbRule>, cache: FtutterOrbCache, client: HttpClient,
    onResult: (String, FtutterOrbVerdict) -> Unit,
    match: (ByteArray, ByteArray) -> Boolean = ::ftutterMatchOrb
) {
    val seeds = rules.map { it.url to Base64.decode(it.features) }
    val distinct = urls.distinct()
    coroutineScope {
        repeat(3) { worker ->
            launch {
                for (index in worker until distinct.size step 3) {
                    val url = distinct[index]
                    currentCoroutineContext().ensureActive()
                    val verdict = try {
                        if (seeds.any { it.first == url }) FtutterOrbVerdict.HIDDEN else {
                            val candidate = cache.get(client, url)
                            val hidden = withContext(AppDispatchers.parsing) {
                                seeds.any { (_, reference) ->
                                    currentCoroutineContext().ensureActive()
                                    match(reference, candidate)
                                }
                            }
                            if (hidden) FtutterOrbVerdict.HIDDEN else FtutterOrbVerdict.CLEAR
                        }
                    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        FtutterOrbVerdict.ERROR
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { FtutterOrbVerdict.ERROR }
                    onResult(url, verdict)
                }
            }
        }
    }
}
