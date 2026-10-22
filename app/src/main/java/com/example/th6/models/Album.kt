package com.example.th6.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.Serializable as JavaSerializable

@Serializable
data class Album @JvmOverloads constructor(
    @SerialName("id")
    var id: String = "",

    @SerialName("owner_uid")
    var ownerUid: String = "",

    @SerialName("name")
    var name: String = "",

    @SerialName("created_at")
    var createdAt: Long = System.currentTimeMillis(),

    @SerialName("cover_image_url")
    var coverImageUrl: String = "",

    @SerialName("photo_count")
    var photoCount: Int = 0,

    @kotlinx.serialization.Transient
    var displayCoverUri: String = "",

    @kotlinx.serialization.Transient
    var displayCoverVideo: Boolean = false
) : JavaSerializable
