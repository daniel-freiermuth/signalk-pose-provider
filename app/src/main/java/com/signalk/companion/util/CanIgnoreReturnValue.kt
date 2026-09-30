package com.signalk.companion.util

/**
 * Marks a function whose result callers may drop because the same outcome is published
 * elsewhere (typically a StateFlow the UI observes). detekt's IgnoredReturnValue honours
 * any annotation with this simple name; use it only with that justification in the KDoc.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class CanIgnoreReturnValue
