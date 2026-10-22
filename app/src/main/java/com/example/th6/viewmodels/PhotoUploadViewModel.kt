package com.example.th6.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.th6.data.AuthRepository
import com.example.th6.data.PhotoRepository
import com.example.th6.data.StorageRepository
import com.example.th6.models.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class PhotoUploadViewModel(
    private val storageRepository: StorageRepository = StorageRepository(),
    private val photoRepository: PhotoRepository = PhotoRepository(),
    private val authRepository: AuthRepository = AuthRepository()
) : ViewModel() {

    sealed class UploadState {
        object Idle : UploadState()
        data class Progress(val message: String) : UploadState()
        data class Success(val photo: Photo, val message: String) : UploadState()
        data class Error(val error: String) : UploadState()
    }

    private val _uploadState = MutableLiveData<UploadState>(UploadState.Idle)
    val uploadState: LiveData<UploadState> get() = _uploadState

    interface UploadCallback {
        fun onSuccess(photo: Photo)
        fun onError(error: String)
    }

    /**
     * Uploads selected image Uri to Supabase Storage, retrieves public URL,
     * creates Photo metadata object and inserts record into Supabase 'photos' table.
     * Completely removes legacy ImgBB / Firestore calls.
     */
    fun uploadSelectedImage(context: Context, uri: Uri, customName: String? = null) {
        viewModelScope.launch {
            _uploadState.value = UploadState.Progress("Đang tải ảnh lên Supabase Storage...")

            val currentUserId = authRepository.getCurrentUserUid()
            if (currentUserId.isNullOrEmpty()) {
                _uploadState.value = UploadState.Error("Người dùng chưa đăng nhập. Vui lòng đăng nhập lại.")
                return@launch
            }

            val fileName = customName?.takeIf { it.isNotBlank() }
                ?: "photo_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg"

            // 1. Upload image to Supabase Storage ('photos_bucket')
            val storageResult = storageRepository.uploadImage(context, uri, fileName)
            if (storageResult is StorageRepository.Result.Failure) {
                _uploadState.value = UploadState.Error("Lỗi upload Supabase Storage: ${storageResult.message}")
                return@launch
            }

            // 3. Create Photo metadata object
            val photo = Photo(
                id = UUID.randomUUID().toString(),
                ownerUid = currentUserId,
                name = fileName,
                imageUrl = storageRepository.getImageUrl(fileName),
                displayUrl = storageRepository.getImageUrl(fileName),
                createdAt = System.currentTimeMillis(),
                localMediaUri = uri.toString(),
                storageProvider = "supabase",
                storagePath = fileName
            )

            // 4. Insert photo record into Supabase 'photos' database table
            try {
                photoRepository.insertPhoto(photo)
                _uploadState.value = UploadState.Success(photo, "Tải ảnh và lưu metadata thành công!")
            } catch (e: Exception) {
                _uploadState.value = UploadState.Error("Lỗi lưu metadata vào Supabase Database: ${e.localizedMessage}")
            }
        }
    }

    /**
     * Overload for uploading raw byte array to Supabase.
     */
    fun uploadSelectedImageBytes(byteArray: ByteArray, fileName: String) {
        viewModelScope.launch {
            _uploadState.value = UploadState.Progress("Đang tải tệp ảnh lên Supabase Storage...")

            val currentUserId = authRepository.getCurrentUserUid()
            if (currentUserId.isNullOrEmpty()) {
                _uploadState.value = UploadState.Error("Người dùng chưa đăng nhập.")
                return@launch
            }

            // 1. Upload byte array to Supabase Storage
            val storageResult = storageRepository.uploadImage(byteArray, fileName)
            if (storageResult is StorageRepository.Result.Failure) {
                _uploadState.value = UploadState.Error("Lỗi upload Supabase Storage: ${storageResult.message}")
                return@launch
            }

            // 3. Create Photo metadata object
            val photo = Photo(
                id = UUID.randomUUID().toString(),
                ownerUid = currentUserId,
                name = fileName,
                imageUrl = storageRepository.getImageUrl(fileName),
                displayUrl = storageRepository.getImageUrl(fileName),
                createdAt = System.currentTimeMillis(),
                storageProvider = "supabase",
                storagePath = fileName
            )

            // 4. Insert photo record into Supabase 'photos' database table
            try {
                photoRepository.insertPhoto(photo)
                _uploadState.value = UploadState.Success(photo, "Tải ảnh thành công!")
            } catch (e: Exception) {
                _uploadState.value = UploadState.Error("Lỗi lưu metadata vào Database: ${e.localizedMessage}")
            }
        }
    }

    // --- Java Async Callback Helpers ---

    fun uploadSelectedImageAsync(context: Context, uri: Uri, customName: String?, callback: UploadCallback) {
        viewModelScope.launch {
            uploadSelectedImage(context, uri, customName)
            when (val state = _uploadState.value) {
                is UploadState.Success -> {
                    withContext(Dispatchers.Main) {
                        callback.onSuccess(state.photo)
                    }
                }
                is UploadState.Error -> {
                    withContext(Dispatchers.Main) {
                        callback.onError(state.error)
                    }
                }
                else -> {}
            }
        }
    }
}
