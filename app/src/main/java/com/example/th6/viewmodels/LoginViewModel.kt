package com.example.th6.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.th6.data.AuthRepository
import kotlinx.coroutines.launch

class LoginViewModel(
    private val authRepository: AuthRepository = AuthRepository()
) : ViewModel() {

    sealed class State {
        object Idle : State()
        object Loading : State()
        data class Success(val userId: String) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> get() = _state

    fun login(email: String, password: String) {
        if (_state.value is State.Loading) return
        _state.value = State.Loading
        viewModelScope.launch {
            when (val result = authRepository.signIn(email, password)) {
                is AuthRepository.AuthResult.Success -> {
                    val userId = result.userId
                    _state.value = if (userId.isNullOrBlank()) {
                        State.Error("Không thể xác nhận phiên đăng nhập. Vui lòng thử lại.")
                    } else {
                        State.Success(userId)
                    }
                }
                is AuthRepository.AuthResult.Failure -> _state.value = State.Error(result.message)
            }
        }
    }

    fun consumeResult() {
        if (_state.value is State.Success || _state.value is State.Error) {
            _state.value = State.Idle
        }
    }
}
