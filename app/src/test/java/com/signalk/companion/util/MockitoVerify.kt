package com.signalk.companion.util

import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.verification.VerificationMode

/**
 * Mockito's `verify(mock, mode).call()` for a [call] that returns a value. The proxy's
 * return value is a dummy: the invocation itself is the assertion, so this is the one
 * place detekt's IgnoredReturnValue is suppressed for verification.
 */
@Suppress("IgnoredReturnValue")
internal fun <T> verifyCalled(mock: T, mode: VerificationMode = times(1), call: T.() -> Any?) {
    verify(mock, mode).call()
}
