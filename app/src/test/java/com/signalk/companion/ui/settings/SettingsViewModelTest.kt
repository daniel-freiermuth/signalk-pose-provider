package com.signalk.companion.ui.settings

import android.content.Context
import android.content.SharedPreferences
import com.signalk.companion.data.model.AuthState
import com.signalk.companion.service.AuthenticationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var viewModel: SettingsViewModel
    private lateinit var authenticationService: AuthenticationService
    private lateinit var authStateFlow: MutableStateFlow<AuthState>
    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        // Create mock auth state flow
        authStateFlow = MutableStateFlow(AuthState())
        
        // Mock AuthenticationService
        authenticationService = mock(AuthenticationService::class.java)
        `when`(authenticationService.authState).thenReturn(authStateFlow)
        
        // Mock Android Context and SharedPreferences
        context = mock(Context::class.java)
        sharedPreferences = mock(SharedPreferences::class.java)
        editor = mock(SharedPreferences.Editor::class.java)
        
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences)
        `when`(sharedPreferences.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenReturn(editor)
        `when`(editor.apply()).then { }
        
        // Default mock values
        `when`(sharedPreferences.getString(eq("server_url"), anyString())).thenReturn("")
        `when`(sharedPreferences.getString(eq("username"), anyString())).thenReturn("")
        `when`(sharedPreferences.getString(eq("password"), anyString())).thenReturn("")
        `when`(sharedPreferences.getString(eq("vessel_id"), anyString())).thenReturn("self")
        
        viewModel = SettingsViewModel(authenticationService)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `local error should not be overwritten by null auth error on auth state update`() = runTest {
        // Set a local error (e.g., from save failure)
        viewModel.saveSettings(context)
        advanceUntilIdle()
        
        // Simulate save error
        `when`(sharedPreferences.edit()).thenThrow(RuntimeException("Storage error"))
        viewModel.saveSettings(context)
        advanceUntilIdle()
        
        val errorAfterSave = viewModel.uiState.value.error
        assertNotNull("Should have error after save failure", errorAfterSave)
        assertTrue("Error should mention save failure", 
            errorAfterSave!!.contains("Failed to save settings"))
        
        // Now emit an auth state update with null error (simulating isLoading change)
        authStateFlow.value = AuthState(isLoading = true, error = null)
        advanceUntilIdle()
        
        // BUG: The local error gets overwritten by null
        // EXPECTED: Local error should be preserved
        val errorAfterAuthUpdate = viewModel.uiState.value.error
        assertNotNull("Local error should not be cleared by auth state update with null error", 
            errorAfterAuthUpdate)
        assertTrue("Error should still mention save failure", 
            errorAfterAuthUpdate!!.contains("Failed to save settings"))
    }

    @Test
    fun `auth error should be displayed when authentication fails`() = runTest {
        // Emit an auth error
        val authError = "Invalid credentials"
        authStateFlow.value = AuthState(error = authError)
        advanceUntilIdle()
        
        assertEquals("Auth error should be shown", authError, viewModel.uiState.value.error)
    }

    @Test
    fun `clearError should clear both local and auth errors`() = runTest {
        // Set an auth error
        val authError = "Invalid credentials"
        authStateFlow.value = AuthState(error = authError)
        advanceUntilIdle()
        
        assertEquals("Should have auth error", authError, viewModel.uiState.value.error)
        
        // Clear error
        viewModel.clearError()
        advanceUntilIdle()
        
        // BUG: Auth service error not cleared, so it will reappear
        // EXPECTED: Both local and auth service errors should be cleared
        verify(authenticationService).clearError()
        
        // Simulate another auth state emission (which would reapply the error if not cleared)
        authStateFlow.value = AuthState(isLoading = false, error = null)
        advanceUntilIdle()
        
        assertNull("Error should remain null after clearing", viewModel.uiState.value.error)
    }

    @Test
    fun `local validation error should not be cleared by auth state updates`() = runTest {
        // Set a validation error
        viewModel.updateServerUrl("")
        viewModel.testConnection(context)
        advanceUntilIdle()
        
        assertEquals("Should have validation error", 
            "Server URL is required", viewModel.uiState.value.error)
        
        // Auth state update with null error
        authStateFlow.value = AuthState(isLoading = false, error = null)
        advanceUntilIdle()
        
        // BUG: Validation error gets cleared
        // EXPECTED: Validation error should persist
        assertNotNull("Validation error should not be cleared", viewModel.uiState.value.error)
    }

    @Test
    fun `new auth error should replace local error`() = runTest {
        // Set a local validation error
        viewModel.updateServerUrl("")
        viewModel.testConnection(context)
        advanceUntilIdle()
        
        assertEquals("Should have validation error", 
            "Server URL is required", viewModel.uiState.value.error)
        
        // Emit an auth error
        val authError = "Connection timeout"
        authStateFlow.value = AuthState(error = authError)
        advanceUntilIdle()
        
        // Auth error should replace local error
        assertEquals("Auth error should replace local error", 
            authError, viewModel.uiState.value.error)
    }

    @Test
    fun `isAuthenticated and isLoggingIn should update from auth state`() = runTest {
        assertFalse("Should not be authenticated initially", 
            viewModel.uiState.value.isAuthenticated)
        assertFalse("Should not be logging in initially", 
            viewModel.uiState.value.isLoggingIn)
        
        // Update to logging in
        authStateFlow.value = AuthState(isLoading = true)
        advanceUntilIdle()
        
        assertTrue("Should be logging in", viewModel.uiState.value.isLoggingIn)
        
        // Update to authenticated
        authStateFlow.value = AuthState(isAuthenticated = true, isLoading = false, token = "test-token")
        advanceUntilIdle()
        
        assertTrue("Should be authenticated", viewModel.uiState.value.isAuthenticated)
        assertFalse("Should not be logging in", viewModel.uiState.value.isLoggingIn)
    }
}
