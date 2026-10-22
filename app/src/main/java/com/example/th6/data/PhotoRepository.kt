package com.example.th6.data

import com.example.th6.models.Album
import com.example.th6.models.Photo
import com.example.th6.models.UserProfile
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

class PhotoRepository {

    interface Callback<T> {
        fun onSuccess(result: T)
        fun onError(error: String)
    }

    companion object {
        const val TABLE_PHOTOS = "photos"
        const val TABLE_ALBUMS = "albums"
        const val TABLE_USERS = "users"
    }

    private val photosTable by lazy { SupabaseManager.client.from(TABLE_PHOTOS) }
    private val albumsTable by lazy { SupabaseManager.client.from(TABLE_ALBUMS) }
    private val usersTable by lazy { SupabaseManager.client.from(TABLE_USERS) }

    // --- PHOTOS ---

    suspend fun getAllPhotos(): List<Photo> {
        return withContext(Dispatchers.IO) {
            photosTable.select().decodeList<Photo>()
        }
    }

    suspend fun getPhotosByUser(userId: String): List<Photo> {
        return withContext(Dispatchers.IO) {
            photosTable.select {
                filter {
                    eq("user_id", userId)
                }
            }.decodeList<Photo>()
        }
    }

    /** Fetch the complete account library in bounded PostgREST pages for media analysis. */
    suspend fun getPhotosByUserPaged(userId: String, pageSize: Long = 500L): List<Photo> = withContext(Dispatchers.IO) {
        require(userId.isNotBlank()) { "A signed-in UID is required" }
        require(pageSize in 1L..1000L) { "Page size must be between 1 and 1000" }
        val result = ArrayList<Photo>()
        var offset = 0L
        while (true) {
            val page = photosTable.select {
                filter { eq("user_id", userId) }
                order("id", Order.ASCENDING)
                range(offset, offset + pageSize - 1)
            }.decodeList<Photo>()
            result.addAll(page)
            if (page.size.toLong() < pageSize) break
            offset += pageSize
        }
        result
    }

    suspend fun getPhotoByUser(userId: String, photoId: String): Photo? = withContext(Dispatchers.IO) {
        photosTable.select {
            filter { eq("user_id", userId); eq("id", photoId) }
            range(0, 0)
        }.decodeList<Photo>().firstOrNull()
    }

    suspend fun getPhotos(userId: String): List<Photo> = getPhotosByUser(userId)

    suspend fun insertPhoto(photo: Photo) {
        withContext(Dispatchers.IO) {
            photosTable.insert(photo)
        }
    }

    suspend fun savePhoto(photo: Photo) {
        withContext(Dispatchers.IO) {
            photosTable.upsert(photo)
        }
    }

    suspend fun saveBackupPhoto(photo: Photo) {
        val uri = photo.localMediaUri
        if (photo.id.isEmpty() && !uri.isNullOrEmpty()) {
            // Include the account in the deterministic key: the same MediaStore URI
            // can exist for different signed-in users on one device.
            photo.id = "backup_" + sha256(photo.ownerUid + "|" + uri)
        } else if (photo.id.isEmpty()) {
            photo.id = "backup_" + sha256(photo.ownerUid + "|" + System.currentTimeMillis())
        }
        withContext(Dispatchers.IO) {
            photosTable.upsert(photo)
        }
    }

    private fun sha256(value: String): String {
        try {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            val result = StringBuilder(digest.size * 2)
            for (b in digest) {
                result.append(String.format(java.util.Locale.US, "%02x", b.toInt() and 0xff))
            }
            return result.toString()
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 unavailable", e)
        }
    }

    suspend fun updatePhoto(photo: Photo) {
        withContext(Dispatchers.IO) {
            photosTable.update(photo) {
                filter {
                    eq("id", photo.id)
                }
            }
        }
    }

    suspend fun updatePhoto(photoId: String, newName: String) {
        withContext(Dispatchers.IO) {
            photosTable.update(
                buildJsonObject { put("title", JsonPrimitive(newName)) }
            ) {
                filter {
                    eq("id", photoId)
                }
            }
        }
    }

    suspend fun toggleFavorite(photoId: String, isFavorite: Boolean) {
        withContext(Dispatchers.IO) {
            photosTable.update(
                buildJsonObject { put("is_favorite", JsonPrimitive(isFavorite)) }
            ) {
                filter {
                    eq("id", photoId)
                }
            }
        }
    }

    suspend fun toggleFavorite(userId: String, photoId: String, isFavorite: Boolean) {
        withContext(Dispatchers.IO) {
            photosTable.update(
                buildJsonObject { put("is_favorite", JsonPrimitive(isFavorite)) }
            ) {
                filter {
                    eq("user_id", userId)
                    eq("id", photoId)
                }
            }
        }
    }

    suspend fun moveToTrash(photoId: String, isTrashed: Boolean) {
        withContext(Dispatchers.IO) {
            photosTable.update(
                buildJsonObject {
                    put("is_trashed", JsonPrimitive(isTrashed))
                    put("trashed_at", JsonPrimitive(if (isTrashed) System.currentTimeMillis() else 0L))
                }
            ) {
                filter {
                    eq("id", photoId)
                }
            }
        }
    }

    suspend fun moveToTrash(userId: String, photoId: String, isTrashed: Boolean) {
        withContext(Dispatchers.IO) {
            photosTable.update(
                buildJsonObject {
                    put("is_trashed", JsonPrimitive(isTrashed))
                    put("trashed_at", JsonPrimitive(if (isTrashed) System.currentTimeMillis() else 0L))
                }
            ) {
                filter {
                    eq("id", photoId)
                    eq("user_id", userId)
                }
            }
            val updated = getPhotosByUser(userId).firstOrNull { it.id == photoId }
                ?: throw IllegalStateException("Photo row was not found for the current user")
            if (updated.isTrashed != isTrashed) {
                throw IllegalStateException("Photo trash state was not updated")
            }
        }
    }

    suspend fun deletePhoto(photoId: String) {
        withContext(Dispatchers.IO) {
            photosTable.delete {
                filter {
                    eq("id", photoId)
                }
            }
        }
    }

    suspend fun deletePhoto(userId: String, photoId: String) {
        withContext(Dispatchers.IO) {
            photosTable.delete {
                filter {
                    eq("id", photoId)
                    eq("user_id", userId)
                }
            }
            if (getPhotosByUser(userId).any { it.id == photoId }) {
                throw IllegalStateException("Photo metadata still exists for the current user")
            }
        }
    }

    suspend fun updatePhotoAlbums(photoId: String, albumIds: List<String>) {
        withContext(Dispatchers.IO) {
            photosTable.update(
                buildJsonObject {
                    put("album_ids", kotlinx.serialization.json.JsonArray(albumIds.map(::JsonPrimitive)))
                }
            ) {
                filter {
                    eq("id", photoId)
                }
            }
        }
    }

    suspend fun updatePhotoAlbums(userId: String, photoId: String, albumIds: List<String>) {
        withContext(Dispatchers.IO) {
            val ownedAlbumIds = getAlbums(userId).map { it.id }.toSet()
            val current = getPhotosByUser(userId).firstOrNull { it.id == photoId }
                ?: throw IllegalStateException("Photo row was not found for the current user")
            val newlyRequestedForeignIds = albumIds.filter { it !in ownedAlbumIds && it !in current.albumIds }
            if (newlyRequestedForeignIds.isNotEmpty()) {
                throw IllegalStateException("Album membership contains an album not owned by the current user")
            }
            // Drop stale IDs left by an album deleted earlier, while retaining every
            // currently owned relationship supplied by the caller.
            val normalizedIds = albumIds.distinct().filter { it in ownedAlbumIds }
            photosTable.update(
                buildJsonObject { put("album_ids", kotlinx.serialization.json.JsonArray(normalizedIds.map(::JsonPrimitive))) }
            ) {
                filter { eq("id", photoId); eq("user_id", userId) }
            }
            val updated = getPhotosByUser(userId).firstOrNull { it.id == photoId }
                ?: throw IllegalStateException("Photo row was not found for the current user")
            if (updated.albumIds.toSet() != normalizedIds.toSet()) throw IllegalStateException("Album membership was not updated")
        }
    }

    suspend fun addPhotoToAlbum(userId: String, photoId: String, albumId: String) {
        val ownedAlbumIds = getAlbums(userId).map { it.id }.toSet()
        if (albumId !in ownedAlbumIds) throw IllegalStateException("Album was not found for the current user")
        val photo = getPhotosByUser(userId).firstOrNull { it.id == photoId }
            ?: throw IllegalStateException("Photo row was not found for the current user")
        val ids = photo.albumIds.filter { it in ownedAlbumIds }.toMutableList()
        if (!ids.contains(albumId)) ids.add(albumId)
        updatePhotoAlbums(userId, photoId, ids)
    }

    suspend fun removePhotoFromAlbum(userId: String, photoId: String, albumId: String) {
        val ownedAlbumIds = getAlbums(userId).map { it.id }.toSet()
        if (albumId !in ownedAlbumIds) throw IllegalStateException("Album was not found for the current user")
        val photo = getPhotosByUser(userId).firstOrNull { it.id == photoId }
            ?: throw IllegalStateException("Photo row was not found for the current user")
        updatePhotoAlbums(userId, photoId, photo.albumIds.filter { it != albumId && it in ownedAlbumIds })
    }

    // --- ALBUMS ---

    suspend fun getAlbums(userId: String): List<Album> {
        return withContext(Dispatchers.IO) {
            albumsTable.select {
                filter {
                    eq("owner_uid", userId)
                }
            }.decodeList<Album>()
        }
    }

    suspend fun createAlbum(album: Album) {
        withContext(Dispatchers.IO) {
            albumsTable.upsert(album)
            if (getAlbums(album.ownerUid).none { it.id == album.id }) {
                throw IllegalStateException("Album was not created for the current user")
            }
        }
    }

    suspend fun deleteAlbum(albumId: String) {
        withContext(Dispatchers.IO) {
            albumsTable.delete {
                filter {
                    eq("id", albumId)
                }
            }
        }
    }

    suspend fun deleteAlbum(userId: String, albumId: String) {
        withContext(Dispatchers.IO) {
            if (getAlbums(userId).none { it.id == albumId }) throw IllegalStateException("Album was not found for the current user")
            albumsTable.delete { filter { eq("id", albumId); eq("owner_uid", userId) } }
            if (getAlbums(userId).any { it.id == albumId }) throw IllegalStateException("Album was not deleted for the current user")
        }
    }

    suspend fun updateAlbumName(albumId: String, newName: String) {
        withContext(Dispatchers.IO) {
            albumsTable.update(
                buildJsonObject { put("name", JsonPrimitive(newName)) }
            ) {
                filter {
                    eq("id", albumId)
                }
            }
        }
    }

    suspend fun updateAlbumName(userId: String, albumId: String, newName: String) {
        withContext(Dispatchers.IO) {
            albumsTable.update(buildJsonObject { put("name", JsonPrimitive(newName)) }) {
                filter { eq("id", albumId); eq("owner_uid", userId) }
            }
            val updated = getAlbums(userId).firstOrNull { it.id == albumId }
                ?: throw IllegalStateException("Album was not found for the current user")
            if (updated.name != newName) throw IllegalStateException("Album name was not updated")
        }
    }

    suspend fun updateAlbumCoverUrl(userId: String, albumId: String, coverUrl: String) {
        withContext(Dispatchers.IO) {
            if (getAlbums(userId).none { it.id == albumId }) throw IllegalStateException("Album was not found for the current user")
            if (coverUrl.isNotEmpty() && getPhotosByUser(userId).none {
                    !it.isTrashed && it.imageUrl == coverUrl && it.albumIds.contains(albumId)
                }) throw IllegalStateException("Cover media must belong to this album and current user")
            albumsTable.update(buildJsonObject { put("cover_image_url", JsonPrimitive(coverUrl)) }) {
                filter { eq("id", albumId); eq("owner_uid", userId) }
            }
            val updated = getAlbums(userId).firstOrNull { it.id == albumId }
                ?: throw IllegalStateException("Album was not found for the current user")
            if (updated.coverImageUrl != coverUrl) throw IllegalStateException("Album cover was not updated")
        }
    }

    // --- USER PROFILE ---

    suspend fun createUserProfile(profile: UserProfile) {
        withContext(Dispatchers.IO) {
            usersTable.upsert(profile)
        }
    }

    suspend fun getUserProfile(uid: String): UserProfile? {
        return withContext(Dispatchers.IO) {
            try {
                usersTable.select {
                    filter {
                        eq("uid", uid)
                    }
                }.decodeSingleOrNull<UserProfile>()
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun updateUserProfile(
        uid: String,
        updates: Map<String, Any>,
        fallbackDisplayName: String,
        fallbackEmail: String
    ) {
        withContext(Dispatchers.IO) {
            require(uid.isNotBlank()) { "A signed-in user is required" }
            require(updates.keys.all { it == "display_name" || it == "photo_url" }) {
                "Unsupported profile field"
            }

            // Email confirmation can leave a valid Auth user without a row in public.users.
            // A PostgREST UPDATE against that missing row succeeds with zero rows, so merge
            // the requested fields into a complete profile and upsert it instead.
            val existing = usersTable.select {
                filter { eq("uid", uid) }
            }.decodeSingleOrNull<UserProfile>()

            val profile = UserProfile(
                uid = uid,
                displayName = (updates["display_name"] as? String)
                    ?: existing?.displayName?.takeIf { it.isNotBlank() }
                    ?: fallbackDisplayName,
                email = existing?.email?.takeIf { it.isNotBlank() } ?: fallbackEmail,
                photoUrl = (updates["photo_url"] as? String)
                    ?: existing?.photoUrl.orEmpty(),
                createdAt = existing?.createdAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
            )

            usersTable.upsert(profile)

            val saved = usersTable.select {
                filter { eq("uid", uid) }
            }.decodeSingleOrNull<UserProfile>()
                ?: throw IllegalStateException("Profile row was not saved")
            if (saved.displayName != profile.displayName || saved.photoUrl != profile.photoUrl) {
                throw IllegalStateException("Profile values did not persist")
            }
        }
    }

    private fun Map<String, Any>.toJsonObject(): JsonObject = buildJsonObject {
        for ((key, value) in this@toJsonObject) {
            when (value) {
                is String -> put(key, JsonPrimitive(value))
                is Boolean -> put(key, JsonPrimitive(value))
                is Int -> put(key, JsonPrimitive(value))
                is Long -> put(key, JsonPrimitive(value))
                is Double -> put(key, JsonPrimitive(value))
                is Float -> put(key, JsonPrimitive(value.toDouble()))
                else -> throw IllegalArgumentException("Unsupported update value type")
            }
        }
    }

    // --- JAVA ASYNC CALLBACK HELPERS ---

    fun getAllPhotosAsync(callback: Callback<List<Photo>>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val list = getAllPhotos()
                withContext(Dispatchers.Main) { callback.onSuccess(list) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi tải ảnh") }
            }
        }
    }

    fun getPhotosByUserAsync(userId: String, callback: Callback<List<Photo>>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val list = getPhotosByUser(userId)
                withContext(Dispatchers.Main) { callback.onSuccess(list) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi tải ảnh của người dùng") }
            }
        }
    }

    fun getPhotosByUserPagedAsync(userId: String, callback: Callback<List<Photo>>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val list = getPhotosByUserPaged(userId)
                withContext(Dispatchers.Main) { callback.onSuccess(list) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi tải đầy đủ thư viện cloud") }
            }
        }
    }

    fun getPhotoByUserAsync(userId: String, photoId: String, callback: Callback<Photo?>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val photo = getPhotoByUser(userId, photoId)
                withContext(Dispatchers.Main) { callback.onSuccess(photo) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Không thể xác minh media cloud") }
            }
        }
    }

    fun getPhotosAsync(userId: String, callback: Callback<List<Photo>>) {
        getPhotosByUserAsync(userId, callback)
    }

    fun savePhotoAsync(photo: Photo, callback: Callback<Void?>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                savePhoto(photo)
                withContext(Dispatchers.Main) { callback.onSuccess(null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi lưu ảnh") }
            }
        }
    }

    fun saveBackupPhotoAsync(photo: Photo, callback: Callback<Void?>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                saveBackupPhoto(photo)
                withContext(Dispatchers.Main) { callback.onSuccess(null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi lưu backup ảnh") }
            }
        }
    }

    fun updatePhotoAsync(photoId: String, newName: String, callback: Callback<Void?>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updatePhoto(photoId, newName)
                withContext(Dispatchers.Main) { callback.onSuccess(null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi cập nhật tên ảnh") }
            }
        }
    }

    fun toggleFavoriteAsync(userId: String, photoId: String, isFavorite: Boolean, callback: Callback<Void?>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                toggleFavorite(userId, photoId, isFavorite)
                withContext(Dispatchers.Main) { callback.onSuccess(null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi cập nhật yêu thích") }
            }
        }
    }

    fun moveToTrashAsync(userId: String, photoId: String, isTrashed: Boolean, callback: Callback<Void?>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                moveToTrash(userId, photoId, isTrashed)
                withContext(Dispatchers.Main) { callback.onSuccess(null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi thùng rác") }
            }
        }
    }

    fun deletePhotoAsync(userId: String, photoId: String, callback: Callback<Void?>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                deletePhoto(userId, photoId)
                withContext(Dispatchers.Main) { callback.onSuccess(null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi xóa ảnh") }
            }
        }
    }

    fun updatePhotoAlbumsAsync(userId: String, photoId: String, albumIds: List<String>, callback: Callback<Void?>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updatePhotoAlbums(userId, photoId, albumIds)
                withContext(Dispatchers.Main) { callback.onSuccess(null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi cập nhật album ảnh") }
            }
        }
    }

    fun addPhotoToAlbumAsync(userId: String, photoId: String, albumId: String, callback: Callback<Void?>) {
        CoroutineScope(Dispatchers.IO).launch {
            try { addPhotoToAlbum(userId, photoId, albumId); withContext(Dispatchers.Main) { callback.onSuccess(null) } }
            catch (e: Exception) { withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Không thể thêm media vào album") } }
        }
    }

    fun removePhotoFromAlbumAsync(userId: String, photoId: String, albumId: String, callback: Callback<Void?>) {
        CoroutineScope(Dispatchers.IO).launch {
            try { removePhotoFromAlbum(userId, photoId, albumId); withContext(Dispatchers.Main) { callback.onSuccess(null) } }
            catch (e: Exception) { withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Không thể gỡ media khỏi album") } }
        }
    }

    fun getAlbumsAsync(userId: String, callback: Callback<List<Album>>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val list = getAlbums(userId)
                withContext(Dispatchers.Main) { callback.onSuccess(list) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi tải album") }
            }
        }
    }

    fun createAlbumAsync(album: Album, callback: Callback<Void?>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                createAlbum(album)
                withContext(Dispatchers.Main) { callback.onSuccess(null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi tạo album") }
            }
        }
    }

    fun deleteAlbumAsync(userId: String, albumId: String, callback: Callback<Void?>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                deleteAlbum(userId, albumId)
                withContext(Dispatchers.Main) { callback.onSuccess(null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi xóa album") }
            }
        }
    }

    fun updateAlbumNameAsync(userId: String, albumId: String, newName: String, callback: Callback<Void?>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateAlbumName(userId, albumId, newName)
                withContext(Dispatchers.Main) { callback.onSuccess(null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi đổi tên album") }
            }
        }
    }

    fun updateAlbumCoverUrlAsync(userId: String, albumId: String, coverUrl: String, callback: Callback<Void?>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateAlbumCoverUrl(userId, albumId, coverUrl)
                withContext(Dispatchers.Main) { callback.onSuccess(null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi cập nhật ảnh bìa album") }
            }
        }
    }

    fun createUserProfileAsync(profile: UserProfile, callback: Callback<Void?>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                createUserProfile(profile)
                withContext(Dispatchers.Main) { callback.onSuccess(null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi tạo hồ sơ") }
            }
        }
    }

    fun getUserProfileAsync(uid: String, callback: Callback<UserProfile>) {
        CoroutineScope(Dispatchers.IO).launch {
            val profile = getUserProfile(uid)
            withContext(Dispatchers.Main) {
                if (profile != null) callback.onSuccess(profile)
                else callback.onError("Không tìm thấy thông tin hồ sơ")
            }
        }
    }

    fun updateUserProfileAsync(
        uid: String,
        updates: Map<String, Any>,
        fallbackDisplayName: String,
        fallbackEmail: String,
        callback: Callback<Void?>
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateUserProfile(uid, updates, fallbackDisplayName, fallbackEmail)
                withContext(Dispatchers.Main) { callback.onSuccess(null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onError(e.localizedMessage ?: "Lỗi cập nhật hồ sơ") }
            }
        }
    }
}
