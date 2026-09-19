package com.signalk.companion.data.model

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SignalKValuesTest {

    @Test
    fun `finiteNumber returns JsonPrimitive for normal double`() {
        val result = SignalKValues.finiteNumber(1.5)
        assertEquals(JsonPrimitive(1.5), result)
    }

    @Test
    fun `finiteNumber returns JsonPrimitive for zero`() {
        val result = SignalKValues.finiteNumber(0.0)
        assertEquals(JsonPrimitive(0.0), result)
    }

    @Test
    fun `finiteNumber returns JsonPrimitive for negative zero`() {
        val result = SignalKValues.finiteNumber(-0.0)
        assertNotNull(result)
    }

    @Test
    fun `finiteNumber returns null for NaN`() {
        val result = SignalKValues.finiteNumber(Double.NaN)
        assertNull(result, "NaN must be dropped to prevent JSON encoding crashes")
    }

    @Test
    fun `finiteNumber returns null for positive infinity`() {
        val result = SignalKValues.finiteNumber(Double.POSITIVE_INFINITY)
        assertNull(result, "Positive infinity must be dropped to prevent JSON encoding crashes")
    }

    @Test
    fun `finiteNumber returns null for negative infinity`() {
        val result = SignalKValues.finiteNumber(Double.NEGATIVE_INFINITY)
        assertNull(result, "Negative infinity must be dropped to prevent JSON encoding crashes")
    }

    @Test
    fun `finiteNumber returns JsonPrimitive for Double MAX_VALUE`() {
        val result = SignalKValues.finiteNumber(Double.MAX_VALUE)
        assertEquals(JsonPrimitive(Double.MAX_VALUE), result)
    }

    @Test
    fun `finiteNumber returns JsonPrimitive for Double MIN_VALUE`() {
        val result = SignalKValues.finiteNumber(Double.MIN_VALUE)
        assertEquals(JsonPrimitive(Double.MIN_VALUE), result)
    }
}
