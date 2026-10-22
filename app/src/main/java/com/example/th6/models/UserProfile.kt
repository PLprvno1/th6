package com.example.th6.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.Serializable as JavaSerializable

@Serializable
data class UserProfile @JvmOverloads constructor(
    @SerialName("uid")
    var uid: String = "",

    @SerialName("display_name")
    var displayName: String = "",

    @SerialName("email")
    var email: String = "",

    @SerialName("photo_url")
    var photoUrl: String = "",

    @SerialName("created_at")
    var createdAt: Long = System.currentTimeMillis()
) : JavaSerializable
