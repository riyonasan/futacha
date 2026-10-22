package com.valoser.futacha.shared.ui.ftutter

internal actual val ftutterOrbAvailable = false
internal actual fun ftutterOrbStoragePath(platformContext: Any): String? = null
internal actual suspend fun ftutterExtractOrb(bytes: ByteArray): ByteArray = error("ORB画像NGはAndroid版で利用できます")
internal actual fun ftutterMatchOrb(left: ByteArray, right: ByteArray): Boolean = error("ORB画像NGはAndroid版で利用できます")
internal actual suspend fun ftutterReadOrbFile(path: String): String? = error("ORB画像NGはAndroid版で利用できます")
internal actual suspend fun ftutterWriteOrbFile(path: String, value: String): Unit = error("ORB画像NGはAndroid版で利用できます")
