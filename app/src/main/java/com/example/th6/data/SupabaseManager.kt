package com.example.th6.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

object SupabaseManager {
    const val SUPABASE_URL = "https://scpalebpnoiqqdcuvkid.supabase.co"
    const val SUPABASE_ANON_KEY = "sb_publishable_tQ9B10avsiCF1FXsgVP4Ag_NeTtSNNH"
    const val AUTH_REDIRECT_SCHEME = "com.example.th6"
    const val AUTH_REDIRECT_HOST = "auth-callback"
    const val AUTH_REDIRECT_URL = "com.example.th6://auth-callback"

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = SUPABASE_URL,
            supabaseKey = SUPABASE_ANON_KEY
        ) {
            // The SDK's implicit recovery callback is returned in the URL fragment.
            // Suppress SDK debug logs because its 3.0.3 fragment parser logs that fragment.
            defaultLogLevel = LogLevel.NONE
            install(Auth)
            install(Postgrest)
            install(Storage)
        }
    }
}
