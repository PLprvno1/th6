package com.example.th6.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.parseSessionFromFragment
import io.github.jan.supabase.auth.status.SessionSource
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.UnknownHostException

class AuthRepository {

    interface AuthCallback {
        fun onSuccess(userId: String?, message: String)
        fun onError(error: String)
    }

    interface SessionCallback {
        fun onResult(isLoggedIn: Boolean, userId: String?, errorMessage: String?)
    }

    interface LogoutCallback {
        fun onComplete(result: LogoutResult)
    }

    data class LogoutResult(
        val localSessionCleared: Boolean,
        val serverSignOutConfirmed: Boolean
    )

    sealed class AuthResult {
        data class Success(val message: String, val userId: String? = null) : AuthResult()
        data class Failure(val exception: Throwable, val message: String) : AuthResult()
    }

    private val auth by lazy {
        SupabaseManager.client.auth
    }

    /**
     * Signs up a new user using email and password via Supabase Auth.
     */
    suspend fun signUp(email: String, pass: String): AuthResult {
        return withContext(Dispatchers.IO) {
            try {
                auth.signUpWith(Email) {
                    this.email = email
                    this.password = pass
                }
                val user = auth.currentUserOrNull()
                AuthResult.Success("Đăng ký thành công!", user?.id)
            } catch (e: Exception) {
                AuthResult.Failure(e, e.localizedMessage ?: "Đăng ký tài khoản thất bại")
            }
        }
    }

    /**
     * Signs in an existing user using email and password via Supabase Auth.
     */
    suspend fun signIn(email: String, pass: String): AuthResult {
        return withContext(Dispatchers.IO) {
            try {
                auth.signInWith(Email) {
                    this.email = email
                    this.password = pass
                }
                val session = auth.currentSessionOrNull()
                val userId = session?.user?.id
                if (userId.isNullOrBlank()) {
                    AuthResult.Failure(IllegalStateException("Supabase returned no authenticated session"),
                        "Không thể xác nhận phiên đăng nhập. Vui lòng thử lại.")
                } else {
                    AuthResult.Success("Đăng nhập thành công!", userId)
                }
            } catch (e: Exception) {
                AuthResult.Failure(e, readableAuthError(e))
            }
        }
    }

    private fun readableAuthError(error: Throwable): String {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is AuthRestException -> {
                    when (current.errorCode) {
                        AuthErrorCode.InvalidCredentials, AuthErrorCode.UserNotFound ->
                            return "Email hoặc mật khẩu không đúng."
                        AuthErrorCode.EmailNotConfirmed ->
                            return "Email của bạn chưa được xác nhận. Hãy kiểm tra hộp thư."
                        AuthErrorCode.OverRequestRateLimit ->
                            return "Bạn thử đăng nhập quá nhiều lần. Vui lòng đợi rồi thử lại."
                        else -> if (current.statusCode >= 500) {
                            return "Máy chủ xác thực đang gặp sự cố. Vui lòng thử lại sau."
                        }
                    }
                }
                is HttpRequestException, is HttpRequestTimeoutException,
                is UnknownHostException, is IOException ->
                    return "Không thể kết nối máy chủ. Kiểm tra mạng rồi thử lại."
                is RestException -> if (current.statusCode >= 500) {
                    return "Máy chủ xác thực đang gặp sự cố. Vui lòng thử lại sau."
                }
            }
            current = current.cause
        }
        return "Đăng nhập chưa thành công. Vui lòng kiểm tra thông tin và thử lại."
    }

    /**
     * Sends password reset email.
     */
    suspend fun resetPassword(email: String): AuthResult {
        return withContext(Dispatchers.IO) {
            try {
                auth.resetPasswordForEmail(email, redirectUrl = SupabaseManager.AUTH_REDIRECT_URL)
                AuthResult.Success("Đã gửi email hướng dẫn đặt lại mật khẩu.")
            } catch (e: Exception) {
                AuthResult.Failure(e, recoveryRequestError(e))
            }
        }
    }

    suspend fun acceptRecoveryFragment(fragment: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val session = auth.parseSessionFromFragment(fragment)
            if (!session.type.equals("recovery", ignoreCase = true)) {
                return@withContext AuthResult.Failure(IllegalStateException(), "Liên kết khôi phục không hợp lệ hoặc đã hết hạn.")
            }
            // Verify with Supabase before persisting the external callback session.
            val user = auth.retrieveUser(session.accessToken)
            auth.importSession(session.copy(user = user), source = SessionSource.External)
            AuthResult.Success("recovery")
        } catch (e: Exception) {
            AuthResult.Failure(e, "Liên kết khôi phục không hợp lệ hoặc đã hết hạn. Hãy yêu cầu liên kết mới.")
        }
    }

    suspend fun updateRecoveredPassword(password: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val session = auth.currentSessionOrNull()
            if (session == null || !session.type.equals("recovery", ignoreCase = true)) {
                return@withContext AuthResult.Failure(IllegalStateException(), "Phiên khôi phục không hợp lệ hoặc đã hết hạn. Hãy yêu cầu liên kết mới.")
            }
            auth.updateUser { this.password = password }
            auth.clearSession()
            AuthResult.Success("Mật khẩu đã được cập nhật. Vui lòng đăng nhập lại.")
        } catch (e: Exception) {
            AuthResult.Failure(e, readableRecoveryUpdateError(e))
        }
    }

    private fun recoveryRequestError(error: Throwable): String = when {
        isNetworkError(error) -> "Không thể kết nối. Kiểm tra mạng rồi thử lại."
        error is AuthRestException && error.statusCode >= 500 -> "Máy chủ xác thực đang gặp sự cố. Vui lòng thử lại sau."
        error is RestException && error.statusCode >= 500 -> "Máy chủ xác thực đang gặp sự cố. Vui lòng thử lại sau."
        else -> "Chưa thể gửi yêu cầu khôi phục. Vui lòng thử lại sau."
    }

    private fun readableRecoveryUpdateError(error: Throwable): String {
        var current: Throwable? = error
        while (current != null) {
            if (current is AuthRestException && current.errorCode == AuthErrorCode.WeakPassword) {
                return "Mật khẩu chưa đáp ứng chính sách bảo mật. Hãy chọn mật khẩu khác."
            }
            if (current is RestException && current.statusCode >= 500 || current is AuthRestException && current.statusCode >= 500) {
                return "Máy chủ xác thực đang gặp sự cố. Vui lòng thử lại sau."
            }
            if (isNetworkError(current)) return "Không thể kết nối. Kiểm tra mạng rồi thử lại."
            current = current.cause
        }
        return "Chưa thể cập nhật mật khẩu. Phiên có thể đã hết hạn; hãy yêu cầu liên kết mới."
    }

    private fun isNetworkError(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is HttpRequestException || current is HttpRequestTimeoutException ||
                current is UnknownHostException || current is IOException) return true
            current = current.cause
        }
        return false
    }

    /**
     * Checks if the current user has a valid active session in Supabase Auth.
     */
    suspend fun checkIsLoggedIn(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                auth.awaitInitialization()
                auth.currentSessionOrNull()?.user?.id?.isNotBlank() == true
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Synchronous session check returning true if current user info is cached.
     */
    fun isLoggedIn(): Boolean {
        return auth.currentUserOrNull() != null && auth.currentSessionOrNull() != null
    }

    /**
     * Signs out the user session from Supabase Auth and clears session tokens.
     */
    suspend fun signOut(): LogoutResult {
        return withContext(Dispatchers.IO) {
            try {
                auth.signOut()
                val cleared = auth.currentSessionOrNull() == null
                if (cleared) LogoutResult(true, true) else {
                    auth.clearSession()
                    LogoutResult(auth.currentSessionOrNull() == null, false)
                }
            } catch (e: Exception) {
                // A network failure may prevent remote token revocation. Always clear the
                // device session so the user cannot continue into private screens here.
                try {
                    auth.clearSession()
                    LogoutResult(auth.currentSessionOrNull() == null, false)
                } catch (_: Exception) {
                    LogoutResult(false, false)
                }
            }
        }
    }

    /**
     * Returns the currently authenticated user info, or null if not logged in.
     */
    fun getCurrentUser(): UserInfo? {
        return auth.currentUserOrNull()
    }

    /**
     * Returns the currently authenticated user's ID, or null.
     */
    fun getCurrentUserUid(): String? {
        return auth.currentUserOrNull()?.id
    }

    // --- Java Async Callback Helpers ---

    fun checkIsLoggedInAsync(callback: SessionCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            var loggedIn = false
            var uid: String? = null
            var errorMessage: String? = null
            try {
                auth.awaitInitialization()
                when (val status = auth.sessionStatus.value) {
                    is SessionStatus.Authenticated -> {
                        uid = status.session.user?.id
                        loggedIn = !uid.isNullOrBlank()
                    }
                    is SessionStatus.RefreshFailure -> {
                        errorMessage = "Không thể xác minh phiên đăng nhập. Kiểm tra mạng rồi thử lại."
                    }
                    else -> Unit
                }
            } catch (_: Exception) {
                errorMessage = "Không thể kiểm tra phiên đăng nhập. Kiểm tra mạng rồi thử lại."
            }
            withContext(Dispatchers.Main) {
                callback.onResult(loggedIn, uid, errorMessage)
            }
        }
    }

    fun signUpAsync(email: String, pass: String, callback: AuthCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            when (val result = signUp(email, pass)) {
                is AuthResult.Success -> {
                    withContext(Dispatchers.Main) {
                        callback.onSuccess(result.userId, result.message)
                    }
                }
                is AuthResult.Failure -> {
                    withContext(Dispatchers.Main) {
                        callback.onError(result.message)
                    }
                }
            }
        }
    }

    fun signInAsync(email: String, pass: String, callback: AuthCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            when (val result = signIn(email, pass)) {
                is AuthResult.Success -> {
                    withContext(Dispatchers.Main) {
                        callback.onSuccess(result.userId, result.message)
                    }
                }
                is AuthResult.Failure -> {
                    withContext(Dispatchers.Main) {
                        callback.onError(result.message)
                    }
                }
            }
        }
    }

    fun resetPasswordAsync(email: String, callback: AuthCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            when (val result = resetPassword(email)) {
                is AuthResult.Success -> {
                    withContext(Dispatchers.Main) {
                        callback.onSuccess(null, result.message)
                    }
                }
                is AuthResult.Failure -> {
                    withContext(Dispatchers.Main) {
                        callback.onError(result.message)
                    }
                }
            }
        }
    }

    fun signOutAsync(callback: LogoutCallback?) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = signOut()
            callback?.let {
                withContext(Dispatchers.Main) {
                    it.onComplete(result)
                }
            }
        }
    }
}
