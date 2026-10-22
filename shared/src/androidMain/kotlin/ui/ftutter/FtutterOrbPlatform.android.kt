package com.valoser.futacha.shared.ui.ftutter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import com.valoser.futacha.shared.media.analysis.TrackingNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal actual val ftutterOrbAvailable = true
internal actual fun ftutterOrbStoragePath(platformContext: Any): String? =
    (platformContext as? android.content.Context)?.applicationContext?.filesDir?.resolve("ftutter-orb-ng-v1.json")?.absolutePath

internal actual suspend fun ftutterExtractOrb(bytes: ByteArray): ByteArray = withContext(Dispatchers.Default) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 4_194_304) {
        "画像を読み取れません（サムネイルの上限は約400万画素です）"
    }
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })) { "画像をデコードできません" }
    try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val gray = ByteArray(pixels.size) { i ->
            val p = pixels[i]
            // OpenCV's integer RGB -> gray conversion, including rounding.
            (((p shr 16 and 255) * 4899 + (p shr 8 and 255) * 9617 + (p and 255) * 1868 + 8192) shr 14).toByte()
        }
        try { requireNotNull(TrackingNative.extractOrb(gray, bitmap.width, bitmap.height)) { "ORBの特徴抽出に失敗しました" } }
        catch (failure: LinkageError) { throw IllegalStateException("画像NGライブラリを読み込めません", failure) }
    } finally { bitmap.recycle() }
}

internal actual fun ftutterMatchOrb(left: ByteArray, right: ByteArray): Boolean = try {
    val result = TrackingNative.matchOrb(left, right)
    check(result >= 0) { "画像NGの照合に失敗しました" }
    result == 1
} catch (failure: LinkageError) { throw IllegalStateException("画像NGライブラリを読み込めません", failure) }

internal actual suspend fun ftutterReadOrbFile(path: String): String? = withContext(Dispatchers.IO) {
    val atomic = AtomicFile(File(path))
    try {
        atomic.openRead().use {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = it.read(buffer)
                if (size < 0) break
                require(output.size() + size <= 8 * 1024 * 1024) { "画像NGの保存データが大きすぎます" }
                output.write(buffer, 0, size)
            }
            val bytes = output.toByteArray()
            require(bytes.size <= 8 * 1024 * 1024) { "画像NGの保存データが大きすぎます" }
            bytes.toString(Charsets.UTF_8)
        }
    } catch (missing: java.io.FileNotFoundException) {
        if (atomic.baseFile.exists() || File("$path.bak").exists()) throw missing
        null
    }
}

internal actual suspend fun ftutterWriteOrbFile(path: String, value: String): Unit = withContext(Dispatchers.IO) {
    val file = File(path)
    check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "画像NGの保存先を作成できません" }
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try {
        stream.write(value.toByteArray(Charsets.UTF_8))
        atomic.finishWrite(stream)
    } catch (failure: Throwable) { atomic.failWrite(stream); throw failure }
}
