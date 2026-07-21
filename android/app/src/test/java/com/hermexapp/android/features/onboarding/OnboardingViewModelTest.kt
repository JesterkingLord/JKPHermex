package com.hermexapp.android.features.onboarding

import com.hermexapp.android.auth.AuthGateway
import com.hermexapp.android.auth.AuthManager
import com.hermexapp.android.auth.PairingOutcome
import com.hermexapp.android.model.AuthStatusResponse
import com.hermexapp.android.network.ApiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingViewModelTest {

    private class FakeAuthGateway(
        var statusResult: () -> AuthStatusResponse = { AuthStatusResponse(authEnabled = true) },
        var configureError: String? = null,
        var pairOutcome: PairingOutcome = PairingOutcome.Failed("not configured"),
    ) : AuthGateway {
        override val lastErrorMessage: StateFlow<String?> get() = errorFlow
        private val errorFlow = MutableStateFlow<String?>(null)
        var configureCalls = 0
            private set
        var pairCalls = 0
            private set
        var lastPairText: String? = null
            private set
        var lastPairDeviceName: String? = null
            private set

        override suspend fun testConnection(serverUrlString: String): AuthStatusResponse =
            statusResult()

        override suspend fun configure(serverUrlString: String, password: String) {
            configureCalls++
            errorFlow.value = configureError
        }

        override suspend fun pairAndConfigure(
            rawText: String,
            deviceName: String,
        ): PairingOutcome {
            pairCalls++
            lastPairText = rawText
            lastPairDeviceName = deviceName
            errorFlow.value = (pairOutcome as? PairingOutcome.Failed)?.reason
            return pairOutcome
        }
    }

    @Test
    fun `test connection reports whether a password is needed`() = runBlocking {
        val viewModel = OnboardingViewModel(FakeAuthGateway())
        viewModel.updateServerUrl("hermes.example.com")

        viewModel.testConnectionNow()

        val state = viewModel.uiState.value
        assertEquals("Connection ok. Password required.", state.connectionMessage)
        assertNull(state.errorMessage)
        assertTrue(state.isPasswordRequired)
        assertFalse(state.isWorking)
    }

    @Test
    fun `test connection against a no-auth server hides the password field`() = runBlocking {
        val gateway = FakeAuthGateway(statusResult = { AuthStatusResponse(authEnabled = false) })
        val viewModel = OnboardingViewModel(gateway)
        viewModel.updateServerUrl("hermes.example.com")

        viewModel.testConnectionNow()

        val state = viewModel.uiState.value
        assertEquals("Connection ok. Password not required.", state.connectionMessage)
        assertFalse(state.isPasswordRequired)
    }

    @Test
    fun `a passkey-only server surfaces the unsupported message`() = runBlocking {
        val gateway = FakeAuthGateway(
            statusResult = {
                AuthStatusResponse(authEnabled = true, passwordAuthEnabled = false)
            },
        )
        val viewModel = OnboardingViewModel(gateway)
        viewModel.updateServerUrl("hermes.example.com")

        viewModel.testConnectionNow()

        assertEquals(AuthManager.PASSKEY_ONLY_MESSAGE, viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isPasswordRequired)
    }

    @Test
    fun `probe failures land in errorMessage`() = runBlocking {
        val gateway = FakeAuthGateway(statusResult = { throw ApiError.Http(503, null) })
        val viewModel = OnboardingViewModel(gateway)
        viewModel.updateServerUrl("hermes.example.com")

        viewModel.testConnectionNow()

        assertEquals(ApiError.Http(503, null).userMessage, viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isWorking)
    }

    @Test
    fun `connect demands a password when the server requires one`() = runBlocking {
        val gateway = FakeAuthGateway()
        val viewModel = OnboardingViewModel(gateway)
        viewModel.updateServerUrl("hermes.example.com")

        viewModel.testConnectionNow()
        viewModel.connectNow()

        assertEquals(AuthManager.EMPTY_PASSWORD_MESSAGE, viewModel.uiState.value.errorMessage)
        assertEquals(0, gateway.configureCalls)
    }

    @Test
    fun `connect probes first when the user skipped test connection`() = runBlocking {
        val gateway = FakeAuthGateway()
        val viewModel = OnboardingViewModel(gateway)
        viewModel.updateServerUrl("hermes.example.com")
        viewModel.updatePassword("hunter2")

        viewModel.connectNow()

        assertEquals(1, gateway.configureCalls)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `configure failures propagate from the gateway`() = runBlocking {
        val gateway = FakeAuthGateway(configureError = "The password is incorrect.")
        val viewModel = OnboardingViewModel(gateway)
        viewModel.updateServerUrl("hermes.example.com")
        viewModel.updatePassword("wrong")

        viewModel.connectNow()

        assertEquals("The password is incorrect.", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `password validation mirrors the iOS rules`() {
        // No auth → no password needed.
        assertNull(
            OnboardingViewModel.passwordValidationMessage(AuthStatusResponse(authEnabled = false), ""),
        )
        // Unknown status → defer to configure.
        assertNull(OnboardingViewModel.passwordValidationMessage(null, ""))
        // Passkey-only → configure reports the specific message instead.
        assertNull(
            OnboardingViewModel.passwordValidationMessage(
                AuthStatusResponse(authEnabled = true, passwordAuthEnabled = false),
                "",
            ),
        )
        // Auth on + blank password → the empty-password message.
        assertEquals(
            AuthManager.EMPTY_PASSWORD_MESSAGE,
            OnboardingViewModel.passwordValidationMessage(
                AuthStatusResponse(authEnabled = true),
                "   ",
            ),
        )
        assertNull(
            OnboardingViewModel.passwordValidationMessage(
                AuthStatusResponse(authEnabled = true),
                "hunter2",
            ),
        )
    }

    @Test
    fun `steps advance welcome to guidance to connect`() {
        val viewModel = OnboardingViewModel(FakeAuthGateway())
        assertEquals(OnboardingViewModel.Step.WELCOME, viewModel.uiState.value.step)

        viewModel.advanceToGuidance()
        assertEquals(OnboardingViewModel.Step.GUIDANCE, viewModel.uiState.value.step)

        viewModel.advanceToConnect()
        assertEquals(OnboardingViewModel.Step.CONNECT, viewModel.uiState.value.step)

        viewModel.backToGuidance()
        assertEquals(OnboardingViewModel.Step.GUIDANCE, viewModel.uiState.value.step)
    }

    @Test
    fun `pairFromText on Paired fills URL and shows success message`() = runBlocking {
        val gateway = FakeAuthGateway(
            pairOutcome = PairingOutcome.Paired(
                serverUrl = "http://100.88.54.29:8642/".toHttpUrlOrNull()!!,
                deviceId = "dev-123",
            ),
        )
        val viewModel = OnboardingViewModel(gateway)
        viewModel.pairFromTextNow("http://100.88.54.29:8642/v1/pair/connect?pair_id=p&token=t")

        val state = viewModel.uiState.value
        assertEquals(1, gateway.pairCalls)
        assertEquals("http://100.88.54.29:8642/", state.serverUrlString)
        assertTrue(
            "success message: ${state.connectionMessage}",
            state.connectionMessage.orEmpty().contains("paired", ignoreCase = true),
        )
        assertTrue(state.connectionMessage.orEmpty().contains("dev-123"))
        assertNull(state.errorMessage)
    }

    @Test
    fun `pairFromText on Prefilled fills the URL but does not connect`() = runBlocking {
        val gateway = FakeAuthGateway(
            pairOutcome = PairingOutcome.Prefilled(
                "http://hermes.example.com/".toHttpUrlOrNull()!!,
            ),
        )
        val viewModel = OnboardingViewModel(gateway)
        viewModel.pairFromTextNow("http://hermes.example.com/api/sessions")

        val state = viewModel.uiState.value
        assertEquals(1, gateway.pairCalls)
        assertEquals("http://hermes.example.com/", state.serverUrlString)
        assertNull(state.errorMessage)
        assertNull(state.connectionMessage)
    }

    @Test
    fun `pairFromText on Failed surfaces the reason`() = runBlocking {
        val gateway = FakeAuthGateway(
            pairOutcome = PairingOutcome.Failed("pairing token expired"),
        )
        val viewModel = OnboardingViewModel(gateway)
        viewModel.pairFromTextNow("http://hermes.example.com/v1/pair/connect?pair_id=p&token=t")

        val state = viewModel.uiState.value
        assertEquals("pairing token expired", state.errorMessage)
        assertNull(state.connectionMessage)
    }

    @Test
    fun `pairFromText passes the device name to the gateway`() = runBlocking {
        val gateway = FakeAuthGateway(
            pairOutcome = PairingOutcome.Prefilled("http://h/".toHttpUrlOrNull()!!),
        )
        val viewModel = OnboardingViewModel(gateway)
        viewModel.pairFromTextNow("http://h/api/x")

        assertEquals(OnboardingViewModel.PairFromTextDeviceName, gateway.lastPairDeviceName)
        assertEquals("http://h/api/x", gateway.lastPairText)
    }
}