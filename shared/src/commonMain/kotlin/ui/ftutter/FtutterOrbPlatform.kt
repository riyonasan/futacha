package com.valoser.futacha.shared.ui.ftutter

// This first integration is Android-only; other modes/platforms retain their existing image NG.
internal expect val ftutterOrbAvailable: Boolean
internal expect fun ftutterOrbStoragePath(platformContext: Any): String?
internal expect suspend fun ftutterExtractOrb(bytes: ByteArray): ByteArray
internal expect fun ftutterMatchOrb(left: ByteArray, right: ByteArray): Boolean
internal expect suspend fun ftutterReadOrbFile(path: String): String?
internal expect suspend fun ftutterWriteOrbFile(path: String, value: String)
