package com.example.th6.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.th6.data.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PasswordRecoveryViewModel : ViewModel() {
    private val repository = AuthRepository()

    @Volatile var requestInFlight = false
        private set
    @Volatile var recoveryInFlight = false
        private set

    interface Callback { fun onResult(success: Boolean, message: String) }

    fun send(email: String, callback: Callback) {
        if (requestInFlight) return
        requestInFlight = true
        viewModelScope.launch {
            val result = repository.resetPassword(email)
            requestInFlight = false
            withContext(Dispatchers.Main) {
                when (result) {
                    is AuthRepository.AuthResult.Success -> callback.onResult(true, result.message)
                    is AuthRepository.AuthResult.Failure -> callback.onResult(false, result.message)
                }
            }
        }
    }

    fun accept(fragment: String, callback: Callback) {
        if (recoveryInFlight) return
        recoveryInFlight = true
        viewModelScope.launch {
            val result = repository.acceptRecoveryFragment(fragment)
            recoveryInFlight = false
            withContext(Dispatchers.Main) {
                when (result) {
                    is AuthRepository.AuthResult.Success -> callback.onResult(true, result.message)
                    is AuthRepository.AuthResult.Failure -> callback.onResult(false, result.message)
                }
            }
        }
    }

    fun update(password: String, callback: Callback) {
        if (recoveryInFlight) return
        recoveryInFlight = true
        viewModelScope.launch {
            val result = repository.updateRecoveredPassword(password)
            recoveryInFlight = false
            withContext(Dispatchers.Main) {
                when (result) {
                    is AuthRepository.AuthResult.Success -> callback.onResult(true, result.message)
                    is AuthRepository.AuthResult.Failure -> callback.onResult(false, result.message)
                }
            }
        }
    }
}
