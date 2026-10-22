package com.example.th6.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.datetime.Instant
import java.io.Serializable as JavaSerializable

object EpochMillisSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("EpochMillis", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeString(Instant.fromEpochMilliseconds(value).toString())
    }

    override fun deserialize(decoder: Decoder): Long {
        val value = decoder.decodeString()
        return value.toLongOrNull() ?: Instant.parse(value).toEpochMilliseconds()
    }
}

@Serializable
data class Photo @JvmOverloads constructor(
    @SerialName("id")
    var id: String = "",

    @SerialName("user_id")
    var ownerUid: String = "",

    @SerialName("title")
    var name: String = "",

    @SerialName("media_url")
    var imageUrl: String = "",

    @Transient
    var displayUrl: String = "",

    @SerialName("created_at")
    @Serializable(with = EpochMillisSerializer::class)
    var createdAt: Long = System.currentTimeMillis(),

    @SerialName("media_type")
    var mediaType: String = "image",

    @SerialName("is_favorite")
    var isFavorite: Boolean = false,

    @SerialName("is_trashed")
    var isTrashed: Boolean = false,

    @SerialName("trashed_at")
    var trashedAt: Long = 0L,

    @SerialName("local_media_id")
    var localMediaId: Long = 0L,

    @SerialName("local_media_uri")
    var localMediaUri: String = "",

    @SerialName("local_date_modified")
    var localDateModified: Long = 0L,

    @SerialName("album_ids")
    var albumIds: List<String> = mutableListOf(),

    @SerialName("storage_provider")
    var storageProvider: String = "supabase",

    @SerialName("content_hash")
    var contentHash: String? = null,

    @SerialName("storage_path")
    var storagePath: String = ""
) : JavaSerializable
