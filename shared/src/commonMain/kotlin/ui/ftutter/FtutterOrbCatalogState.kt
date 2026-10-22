package com.valoser.futacha.shared.ui.ftutter

import androidx.compose.runtime.*
import com.valoser.futacha.shared.model.CatalogItem

internal class FtutterOrbCatalogState(
    val board: String, val available: Boolean, private val https: Boolean = false,
    showBeforeCheck: Boolean = false, private val cache: FtutterOrbCache? = null
) {
    var showBeforeCheck by mutableStateOf(showBeforeCheck)
    var rules by mutableStateOf<List<FtutterOrbRule>?>(null)
    var loadError by mutableStateOf<String?>(null)
    var target by mutableStateOf<CatalogItem?>(null)
    var managing by mutableStateOf(false)
    var checkedRules by mutableStateOf<List<FtutterOrbRule>?>(null)
    var verdicts by mutableStateOf<Map<String, FtutterOrbVerdict>>(emptyMap())
    val active: List<FtutterOrbRule> get() = rules.orEmpty().filter { it.board == board || it.board == "*" }
    fun url(item: CatalogItem): String? = ftutterOrbThumbnail(item)?.let { ftutterNetworkUrl(it, https) }
    fun displayVerdict(item: CatalogItem): FtutterOrbVerdict? {
        val verdict = verdict(item)
        if (verdict != null || !showBeforeCheck || rules == null || loadError != null) return verdict
        // The registered URL itself needs no image analysis, even in display-first mode.
        return if (active.any { it.url == url(item) }) FtutterOrbVerdict.HIDDEN else FtutterOrbVerdict.CLEAR
    }
    fun verdict(item: CatalogItem): FtutterOrbVerdict? {
        if (!available) return FtutterOrbVerdict.CLEAR
        if (rules == null) return if (loadError != null) FtutterOrbVerdict.ERROR else null
        if (active.isEmpty()) return FtutterOrbVerdict.CLEAR
        val url = url(item) ?: return FtutterOrbVerdict.CLEAR
        // A new rule invalidates the previous verdict in composition, before its effect starts.
        return (if (checkedRules == rules) verdicts[url] else null) ?: cache?.verdict(url, active)
    }
}
