package com.example.th6.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.storage.upload
import io.github.jan.supabase.storage.FileObject
import io.ktor.http.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Storage operations for the public PhotoVault bucket. URI uploads use SDK streaming APIs. */
class StorageRepository {
    interface UploadCallback {
        fun onSuccess(publicUrl: String)
        fun onError(error: String)
    }
    interface DeleteCallback {
        fun onSuccess()
        fun onError(error: String)
    }
    interface UsageCallback {
        fun onSuccess(bytes: Long)
        fun onError(error: String)
    }
    sealed class Result {
        data class Success(val publicUrl: String) : Result()
        data class Failure(val exception: Throwable, val message: String) : Result()
    }
    companion object {
        const val BUCKET_NAME = "photos_bucket"
        const val MAX_FILE_BYTES = 50L * 1024L * 1024L
    }

    private val bucket by lazy { SupabaseManager.client.storage.from(BUCKET_NAME) }
    fun getImageUrl(path: String): String = bucket.publicUrl(path)

    fun objectPathFromPublicUrl(publicUrl: String?): String {
        if (publicUrl.isNullOrBlank()) return ""
        val segments = Uri.parse(publicUrl).pathSegments
        val index = segments.indexOf(BUCKET_NAME)
        return if (index >= 0 && index + 1 < segments.size) segments.subList(index + 1, segments.size).joinToString("/") else ""
    }

    /** Upload a content URI without buffering the entire image/video in memory. */
    suspend fun uploadMedia(context: Context, uri: Uri, path: String, mimeType: String?): Result = withContext(Dispatchers.IO) {
        try {
            val appContext = context.applicationContext
            val size = querySize(appContext, uri)
            if (size <= 0L) return@withContext Result.Failure(IllegalArgumentException("Unknown media size"), "Không xác định được dung lượng tệp.")
            if (size > MAX_FILE_BYTES) return@withContext Result.Failure(IllegalArgumentException("Media exceeds bucket limit"), "Tệp vượt giới hạn 50 MiB của bucket.")
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { }
                ?: return@withContext Result.Failure(IllegalArgumentException("Unreadable URI"), "Không còn quyền đọc tệp đã chọn.")
            bucket.upload(path = path, uri = uri) {
                upsert = true
                contentType = mimeType?.takeIf { it.contains('/') }?.let { ContentType.parse(it) }
            }
            Result.Success(bucket.publicUrl(path))
        } catch (e: Exception) {
            val message = when {
                e is SecurityException -> "Không còn quyền đọc tệp. Hãy chọn lại tệp và thử lại."
                e.cause is java.io.IOException || e is java.io.IOException -> "Không thể đọc tệp hoặc kết nối mạng. Kiểm tra quyền truy cập và mạng rồi thử lại."
                else -> e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Không thể tải tệp lên Supabase Storage."
            }
            Result.Failure(e, message)
        }
    }

    private fun querySize(context: Context, uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }

    suspend fun uploadImage(byteArray: ByteArray, fileName: String): Result = withContext(Dispatchers.IO) {
        try {
            if (byteArray.size > MAX_FILE_BYTES) return@withContext Result.Failure(IllegalArgumentException("Image exceeds limit"), "Tệp vượt giới hạn 50 MiB.")
            bucket.upload(path = fileName, data = byteArray) { upsert = true }
            Result.Success(bucket.publicUrl(fileName))
        } catch (e: Exception) { Result.Failure(e, e.localizedMessage ?: "Lỗi upload ảnh lên Supabase Storage") }
    }

    suspend fun uploadImage(context: Context, uri: Uri, fileName: String): Result = uploadMedia(context, uri, fileName, context.contentResolver.getType(uri))

    suspend fun deleteImage(path: String): Result = withContext(Dispatchers.IO) {
        try { bucket.delete(path); Result.Success(bucket.publicUrl(path)) }
        catch (e: Exception) { Result.Failure(e, e.localizedMessage ?: "Lỗi xóa tệp khỏi Supabase Storage") }
    }

    /** Sum the actual object sizes visible to the signed-in user through Storage RLS. */
    suspend fun getCurrentUserStorageBytes(userId: String): Long = withContext(Dispatchers.IO) {
        require(userId.isNotBlank()) { "A signed-in user is required" }
        val pendingPrefixes = ArrayDeque<String>()
        pendingPrefixes.add("")
        var total = 0L
        while (pendingPrefixes.isNotEmpty()) {
            val prefix = pendingPrefixes.removeFirst()
            var offset = 0
            while (true) {
                val page = bucket.list(prefix = prefix) {
                    limit = 100
                    this.offset = offset
                }
                for (item: FileObject in page) {
                    if (item.id == null) {
                        pendingPrefixes.add(prefix + item.name.trimEnd('/') + "/")
                    } else {
                        val rawSize = item.metadata?.get("size")?.toString()?.trim('"')
                        val size = rawSize?.toLongOrNull()
                            ?: throw IllegalStateException("Storage did not return a valid size for ${item.name}")
                        if (size < 0L) throw IllegalStateException("Storage returned a negative object size")
                        total = Math.addExact(total, size)
                    }
                }
                if (page.size < 100) break
                offset += page.size
            }
        }
        total
    }

    fun getCurrentUserStorageBytesAsync(userId: String, callback: UsageCallback) = CoroutineScope(Dispatchers.IO).launch {
        try {
            val bytes = getCurrentUserStorageBytes(userId)
            withContext(Dispatchers.Main) { callback.onSuccess(bytes) }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Không thể tải dung lượng Storage") }
        }
    }

    fun uploadImageAsync(byteArray: ByteArray, fileName: String, callback: UploadCallback) = CoroutineScope(Dispatchers.IO).launch {
        dispatchUpload(uploadImage(byteArray, fileName), callback)
    }
    fun uploadImageAsync(context: Context, uri: Uri, fileName: String, callback: UploadCallback) = CoroutineScope(Dispatchers.IO).launch {
        dispatchUpload(uploadImage(context, uri, fileName), callback)
    }
    fun uploadMediaAsync(context: Context, uri: Uri, path: String, mimeType: String?, callback: UploadCallback) = CoroutineScope(Dispatchers.IO).launch {
        dispatchUpload(uploadMedia(context, uri, path, mimeType), callback)
    }
    private suspend fun dispatchUpload(result: Result, callback: UploadCallback) {
        withContext(Dispatchers.Main) {
            when (result) {
                is Result.Success -> callback.onSuccess(result.publicUrl)
                is Result.Failure -> callback.onError(result.message)
            }
        }
    }
    fun deleteImageAsync(path: String, callback: DeleteCallback) = CoroutineScope(Dispatchers.IO).launch {
        when (val result = deleteImage(path)) {
            is Result.Success -> withContext(Dispatchers.Main) { callback.onSuccess() }
            is Result.Failure -> withContext(Dispatchers.Main) { callback.onError(result.message) }
        }
    }
}
