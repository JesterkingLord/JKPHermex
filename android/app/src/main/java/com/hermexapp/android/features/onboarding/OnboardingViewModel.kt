package com.hermexapp.android.features.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermexapp.android.auth.AuthGateway
import com.hermexapp.android.auth.AuthManager
import com.hermexapp.android.auth.PairingOutcome
import com.hermexapp.android.model.AuthStatusResponse
import com.hermexapp.android.network.ApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Port of the iOS `OnboardingViewModel`: drives the welcome → guidance →
 * connect flow, the test-connection probe, and password validation. All logic
 * lives in the suspend cores ([testConnectionNow]/[connectNow]/[pairFromTextNow])
 * so JVM tests exercise them directly; the UI goes through the launching
 * wrappers.
 */
class OnboardingViewModel(
    private val authGateway: AuthGateway,
    savedServerUrl: String? = null,
    initialErrorMessage: String? = null,
) : ViewModel() {

    enum class Step { WELCOME, GUIDANCE, CONNECT }

    data class UiState(
        val step: Step = Step.WELCOME,
        val serverUrlString: String = "",
        val password: String = "",
        val authStatus: AuthStatusResponse? = null,
        val connectionMessage: String? = null,
        val errorMessage: String? = null,
        val isWorking: Boolean = false,
    ) {
        /**
         * No auth → no password. Passkey-only (auth on, password auth explicitly
         * off) → hide the field; connect() surfaces the unsupported message.
         * Unknown (null) keeps the show-the-field default. Mirrors iOS.
         */
        val isPasswordRequired: Boolean
            get() = authStatus?.authEnabled != false && authStatus?.passwordAuthEnabled != false
    }

    private val _uiState = MutableStateFlow(
        UiState(serverUrlString = savedServerUrl.orEmpty(), errorMessage = initialErrorMessage),
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun advanceToGuidance() = _uiState.update { it.copy(step = Step.GUIDANCE) }
    fun advanceToConnect() = _uiState.update { it.copy(step = Step.CONNECT) }
    fun backToWelcome() = _uiState.update { it.copy(step = Step.WELCOME) }
    fun backToGuidance() = _uiState.update { it.copy(step = Step.GUIDANCE) }

    fun updateServerUrl(value: String) = _uiState.update { it.copy(serverUrlString = value) }
    fun updatePassword(value: String) = _uiState.update { it.copy(password = value) }

    fun testConnection() {
        viewModelScope.launch { testConnectionNow() }
    }

    fun connect() {
        viewModelScope.launch { connectNow() }
    }

    /**
     * v0.3.0+: handles a QR/pasted pairing URL from the OnboardingScreen's
     * "Scan QR or paste URL" affordance. On [PairingOutcome.Paired] we
     * transition to LoggedIn (no password needed yet — grant is stored but
     * not yet consumed by ApiClient; that's v0.4.0+); on
     * [PairingOutcome.Prefilled] we just fill the URL field; on
     * [PairingOutcome.Failed] the reason is surfaced via errorMessage and
     * the user keeps the typed URL.
     */
    fun pairFromText(rawText: String) {
        viewModelScope.launch { pairFromTextNow(rawText) }
    }

    suspend fun pairFromTextNow(rawText: String) {
        _uiState.update {
            it.copy(isWorking = true, errorMessage = null, connectionMessage = null)
        }
        try {
            val outcome = authGateway.pairAndConfigure(
                rawText = rawText,
                deviceName = PairFromTextDeviceName,
            )
            when (outcome) {
                is PairingOutcome.Paired -> _uiState.update {
                    it.copy(
                        serverUrlString = outcome.serverUrl.toString(),
                        connectionMessage = "Device paired as ${outcome.deviceId}.",
                    )
                }
                is PairingOutcome.Prefilled -> _uiState.update {
                    it.copy(serverUrlString = outcome.serverUrl.toString())
                }
                is PairingOutcome.Failed -> _uiState.update {
                    it.copy(errorMessage = outcome.reason)
                }
            }
        } finally {
            _uiState.update { it.copy(isWorking = false) }
        }
    }

    suspend fun testConnectionNow() {
        _uiState.update { it.copy(errorMessage = null, connectionMessage = null, isWorking = true) }
        try {
            val status = authGateway.testConnection(_uiState.value.serverUrlString)
            val message = when {
                status.authEnabled == true && status.passwordAuthEnabled == false -> null
                status.authEnabled == true -> "Connection ok. Password required."
                else -> "Connection ok. Password not required."
            }
            val error = if (status.authEnabled == true && status.passwordAuthEnabled == false) {
                AuthManager.PASSKEY_ONLY_MESSAGE
            } else {
                null
            }
            _uiState.update {
                it.copy(authStatus = status, connectionMessage = message, errorMessage = error)
            }
        } catch (e: ApiError) {
            _uiState.update { it.copy(errorMessage = e.userMessage) }
        } finally {
            _uiState.update { it.copy(isWorking = false) }
        }
    }

    suspend fun connectNow() {
        _uiState.update { it.copy(errorMessage = null, connectionMessage = null) }

        passwordValidationMessage(_uiState.value.authStatus, _uiState.value.password)?.let { message ->
            _uiState.update { it.copy(errorMessage = message) }
            return
        }

        _uiState.update { it.copy(isWorking = true) }
        try {
            if (_uiState.value.authStatus == null) {
                val status = try {
                    authGateway.testConnection(_uiState.value.serverUrlString)
                } catch (e: ApiError) {
                    _uiState.update { it.copy(errorMessage = e.userMessage) }
                    return
                }
                _uiState.update { it.copy(authStatus = status) }

                passwordValidationMessage(status, _uiState.value.password)?.let { message ->
                    _uiState.update { it.copy(errorMessage = message) }
                    return
                }
            }

            authGateway.configure(_uiState.value.serverUrlString, _uiState.value.password)
            _uiState.update { it.copy(errorMessage = authGateway.lastErrorMessage.value) }
        } finally {
            _uiState.update { it.copy(isWorking = false) }
        }
    }

    companion object {
        /** Mirrors the iOS `passwordValidationMessage`. */
        fun passwordValidationMessage(authStatus: AuthStatusResponse?, password: String): String? {
            if (authStatus?.authEnabled != true) return null
            // Passkey-only servers don't take a password — configure() reports the
            // specific unsupported message instead of demanding one here.
            if (authStatus.passwordAuthEnabled == false) return null
            return if (password.trim().isEmpty()) AuthManager.EMPTY_PASSWORD_MESSAGE else null
        }

        /**
         * Default device name the server sees when the user pastes a pairing
         * URL without typing one. Matches the desktop's browser-handoff page
         * default ("My phone"). The iOS port overrides this with the user's
         * actual device name from UIDevice.
         */
        const val PairFromTextDeviceName = "JKP Mobile"
    }
}