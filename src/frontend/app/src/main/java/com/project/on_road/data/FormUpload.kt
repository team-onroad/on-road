package com.project.on_road.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** 올릴 양식 파일 (사진은 JPG 로 다시 만든 것) */
class UploadFile(val bytes: ByteArray, val name: String?, val isHwp: Boolean = false, val mime: String = "")

/**
 * 고른 사진·PDF 를 서버에 올릴 형태로 (POST /documents/upload, 10MB 이하).
 * - 사진: 긴 변 1400px 로 줄이고, 돌아간 사진은 바로 세우고, JPG 로 다시 만든다 (위치 정보 등 EXIF 없음, HEIC 도 JPG 로)
 * - PDF: 그대로. 한글(hwp·hwpx)은 isHwp (서버 대신 앱 안에서 항목만 나눈다)
 */
object FormUpload {
    private const val MAX_SIDE = 1400 // 서버가 1280px 로 다시 줄인다. 크게 보내면 올리는 시간만 길어진다
    const val MAX_BYTES = 10 * 1024 * 1024

    class TooBig : Exception("파일이 너무 커요. 10MB 이하로 올려 주세요.")
    class NotSupported : Exception("사진이나 PDF 파일만 올릴 수 있어요.")

    suspend fun prepare(context: Context, uri: Uri): UploadFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val mime = resolver.getType(uri).orEmpty()
        val lower = name.orEmpty().lowercase()
        val raw = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw NotSupported()
        when {
            lower.endsWith(".hwp") || lower.endsWith(".hwpx") -> UploadFile(raw, name, isHwp = true, mime = mime)
            mime == "application/pdf" || lower.endsWith(".pdf") -> {
                if (raw.size > MAX_BYTES) throw TooBig()
                UploadFile(raw, name, mime = "application/pdf")
            }
            mime.startsWith("image/") || uri.scheme == "file" || name == null -> UploadFile(photo(raw), name, mime = "image/jpeg")
            else -> throw NotSupported()
        }
    }

    /** 사진을 줄이고 바로 세워 JPG 로 */
    fun photo(raw: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0) throw NotSupported()
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw NotSupported()
        val longest = maxOf(bmp.width, bmp.height)
        if (longest > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / longest
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        }
        val degrees = runCatching {
            when (ExifInterface(raw.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (degrees != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        }
    }
}
