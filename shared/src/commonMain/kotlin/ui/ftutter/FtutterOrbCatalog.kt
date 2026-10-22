package com.valoser.futacha.shared.ui.ftutter

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.valoser.futacha.shared.model.CatalogItem
import com.valoser.futacha.shared.ui.FutachaAppLockAwareWindow
import com.valoser.futacha.shared.ui.compat.CompatImageNgRegistrationDialog
import com.valoser.futacha.shared.ui.futaber.FutaberMediaServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import coil3.compose.LocalPlatformContext
import kotlin.io.encoding.Base64

@Composable
internal fun rememberFtutterOrbCatalog(
    board: String, items: List<CatalogItem>, refreshTick: Int, services: FutaberMediaServices?,
    cache: FtutterOrbCache, onNotice: (String) -> Unit,
    visibleThreadUrls: () -> Set<String>
): FtutterOrbCatalogState {
    val platformContext = LocalPlatformContext.current
    val https = LocalFtutterPreferences.current.ftutterBool(FtutterKeys.USE_HTTPS, false)
    val path = remember(platformContext) { ftutterOrbStoragePath(platformContext) }
    val available = ftutterOrbAvailable && path != null
    val state = remember(board, path, available, https) { FtutterOrbCatalogState(board, available, https) }
    val store = remember(path) { path?.let { ftutterOrbStore(it) } }
    val scope = rememberCoroutineScope()
    val currentNotice by rememberUpdatedState(onNotice)
    val currentVisibleThreadUrls by rememberUpdatedState(visibleThreadUrls)
    LaunchedEffect(store, state, refreshTick) {
        if (!available || store == null) return@LaunchedEffect
        try { state.rules = store.load(); state.loadError = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            state.loadError = failure.message ?: "画像NGの登録を読み込めません"
            currentNotice("画像NGを読み込めないため画像を伏せています：${state.loadError}")
        }
    }
    val rules = state.rules
    val client = services?.httpClient
    LaunchedEffect(state, rules, items, refreshTick, client) {
        if (!available || rules == null) return@LaunchedEffect
        if (state.checkedRules != rules) {
            state.verdicts = emptyMap()
            state.checkedRules = rules
        }
        if (state.active.isEmpty()) return@LaunchedEffect
        if (client == null) {
            currentNotice("通信機能を初期化できないため、画像NGの判定前の画像を伏せています")
            return@LaunchedEffect
        }
        val urls = items.mapNotNull(state::url).toSet()
        val urlByThread = items.associate { it.threadUrl to state.url(it) }
        state.verdicts = state.verdicts.filterKeys { it in urls }
        var failureNoticed = false
        ftutterCheckOrb(urls.filter { state.verdicts[it] !in setOf(FtutterOrbVerdict.CLEAR, FtutterOrbVerdict.HIDDEN) },
            state.active, cache, client, onResult = { url, verdict ->
            state.verdicts = state.verdicts + (url to verdict)
            if (verdict == FtutterOrbVerdict.ERROR && !failureNoticed) {
                failureNoticed = true
                currentNotice("画像NGを判定できない画像は伏せています。更新で再試行できます")
            }
        }, preferredUrls = { currentVisibleThreadUrls().mapNotNull { urlByThread[it] }.toSet() })
    }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    state.target?.let { item ->
        val url = state.url(item)
        if (url != null && !busy && available) CompatImageNgRegistrationDialog(url, item.title.orEmpty().take(300),
            onDismiss = { state.target = null }) { memo, localOnly ->
            busy = true
            scope.launch {
                try {
                    val features = cache.get(checkNotNull(client) { "通信機能を初期化できません" }, url)
                    require(ftutterOrbFeatureCount(features) >= 8) { "この画像にはORBで識別できる特徴が足りません" }
                    state.rules = requireNotNull(store).add(FtutterOrbRule(url, if (localOnly) board else "*", memo, Base64.encode(features)))
                    state.target = null
                    currentNotice("ORB画像NGに登録しました")
                } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    error = "画像の取得が時間切れになりました。再試行してください"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "画像を登録できません" }
                finally { busy = false }
            }
        }
    }
    if (state.managing && !busy) FutachaAppLockAwareWindow { AlertDialog(
        onDismissRequest = { state.managing = false },
        title = { Text("カタログのORB画像NG") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("登録した画像の加工違いを照合します。同じ顔などを共有する別画像も隠れる場合があります。スレッド内の画像には適用しません。")
            state.loadError?.let { Text(it) }
            if (state.active.isEmpty()) Text("この板に適用される登録はありません")
            state.active.asReversed().forEach { rule ->
                Text((if (rule.board == "*") "全ての板：" else "この板：") + rule.memo.ifBlank { rule.url.substringAfterLast('/') })
                TextButton(onClick = {
                    busy = true
                    scope.launch {
                        try { state.rules = requireNotNull(store).remove(rule) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "登録を削除できません" }
                        finally { busy = false }
                    }
                }) { Text("登録を削除") }
            }
        } },
        confirmButton = { TextButton(onClick = { state.managing = false }) { Text("閉じる") } }
    ) }
    if (busy) FutachaAppLockAwareWindow { AlertDialog(onDismissRequest = {}, text = { Text("画像NGを処理しています…") }, confirmButton = {}) }
    error?.let { message -> FutachaAppLockAwareWindow { AlertDialog(
        onDismissRequest = { error = null }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("閉じる") } }
    ) } }
    return state
}
