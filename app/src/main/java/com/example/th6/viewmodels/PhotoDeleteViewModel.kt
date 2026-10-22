package com.example.th6.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.th6.data.PhotoRepository
import com.example.th6.data.StorageRepository
import com.example.th6.models.Photo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoDeleteViewModel(
    private val storageRepository: StorageRepository = StorageRepository(),
    private val photoRepository: PhotoRepository = PhotoRepository()
) : ViewModel() {

    sealed class DeleteState {
        object Idle : DeleteState()
        data class Progress(val message: String = "Đang xóa ảnh khỏi Supabase...") : DeleteState()
        data class Success(val photoId: String, val message: String = "Xóa ảnh thành công!") : DeleteState()
        data class Error(val error: String) : DeleteState()
    }

    private val _deleteState = MutableLiveData<DeleteState>(DeleteState.Idle)
    val deleteState: LiveData<DeleteState> get() = _deleteState

    interface DeleteCallback {
        fun onSuccess(photoId: String)
        fun onError(error: String)
    }

    /**
     * Executes photo deletion flow on Supabase:
     * (1) Calls StorageRepository.deleteImage to delete the physical file on Supabase Storage ('photos_bucket').
     * (2) Calls PhotoRepository.deletePhoto to delete the metadata record in Supabase 'photos' table.
     * Completely removes all legacy Firebase logic.
     */
    fun deletePhoto(photo: Photo) {
        viewModelScope.launch {
            _deleteState.value = DeleteState.Progress("Đang xóa tệp và dữ liệu khỏi Supabase...")

            val photoId = photo.id
            if (photoId.isBlank()) {
                _deleteState.value = DeleteState.Error("ID ảnh không hợp lệ.")
                return@launch
            }

            val fileName = photo.storagePath.takeIf { it.isNotBlank() }
                ?: storageRepository.objectPathFromPublicUrl(photo.imageUrl)

            var storageError: String? = null
            var databaseError: String? = null

            // 1. Delete physical file from Supabase Storage ('photos_bucket')
            if (fileName.isNotBlank()) {
                val storageResult = storageRepository.deleteImage(fileName)
                if (storageResult is StorageRepository.Result.Failure) {
                    storageError = storageResult.message
                }
            }

            // 2. Delete metadata record from Supabase 'photos' database table
            try {
                photoRepository.deletePhoto(photoId)
            } catch (e: Exception) {
                databaseError = e.localizedMessage ?: "Lỗi xóa dữ liệu trên Database"
            }

            // Report result to UI
            if (databaseError != null) {
                _deleteState.value = DeleteState.Error("Lỗi xóa cơ sở dữ liệu: $databaseError")
            } else if (storageError != null) {
                _deleteState.value = DeleteState.Success(photoId, "Đã xóa bản ghi dữ liệu (Lưu ý Storage: $storageError)")
            } else {
                _deleteState.value = DeleteState.Success(photoId, "Đã xóa hoàn toàn tệp và dữ liệu ảnh khỏi Supabase!")
            }
        }
    }

    /**
     * Overload accepting photoId and fileName directly.
     */
    fun deletePhoto(photoId: String, fileName: String) {
        viewModelScope.launch {
            _deleteState.value = DeleteState.Progress("Đang xóa ảnh khỏi Supabase...")

            // 1. Delete physical file on Supabase Storage
            val storageResult = storageRepository.deleteImage(fileName)

            // 2. Delete record from Supabase 'photos' database table
            try {
                photoRepository.deletePhoto(photoId)
                if (storageResult is StorageRepository.Result.Failure) {
                    _deleteState.value = DeleteState.Success(photoId, "Đã xóa bản ghi cơ sở dữ liệu.")
                } else {
                    _deleteState.value = DeleteState.Success(photoId, "Xóa ảnh khỏi Supabase thành công!")
                }
            } catch (e: Exception) {
                _deleteState.value = DeleteState.Error("Lỗi xóa bản ghi Database: ${e.localizedMessage}")
            }
        }
    }

    // --- Java Async Callback Helpers ---

    fun deletePhotoAsync(photo: Photo, callback: DeleteCallback) {
        viewModelScope.launch {
            deletePhoto(photo)
            when (val state = _deleteState.value) {
                is DeleteState.Success -> {
                    withContext(Dispatchers.Main) {
                        callback.onSuccess(state.photoId)
                    }
                }
                is DeleteState.Error -> {
                    withContext(Dispatchers.Main) {
                        callback.onError(state.error)
                    }
                }
                else -> {}
            }
        }
    }
}
