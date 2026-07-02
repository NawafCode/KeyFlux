package com.keyflux

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for clipboard preference getters via companion object static methods.
 */
class ClipboardPreferencesTests {

    // --- resolveClipboardTextSize ---

    @Test fun `size defaults to 10 when empty map`() { assertEquals(10, PreferencesManager.resolveClipboardTextSize(emptyMap())) }

    @Test fun `size reads keyflux_clip_size`() { assertEquals(25, PreferencesManager.resolveClipboardTextSize(mapOf("keyflux_clip_size" to 25))) }

    @Test fun `size defaults when key is missing`() { assertEquals(10, PreferencesManager.resolveClipboardTextSize(mapOf("other_key" to 99))) }

    @Test fun `size handles non-Int value gracefully`() { assertEquals(10, PreferencesManager.resolveClipboardTextSize(mapOf("keyflux_clip_size" to "not_a_number"))) }

    @Test fun `size of 0 returns 0`() { assertEquals(0, PreferencesManager.resolveClipboardTextSize(mapOf("keyflux_clip_size" to 0))) }

    @Test fun `size of 100 returns 100`() { assertEquals(100, PreferencesManager.resolveClipboardTextSize(mapOf("keyflux_clip_size" to 100))) }

    // --- resolveClipboardTextTime ---

    private val threeDaysMillis = 3L * 1000 * 60 * 60 * 24

    @Test fun `time defaults to 3 days`() { assertEquals(threeDaysMillis, PreferencesManager.resolveClipboardTextTime(emptyMap())) }

    @Test fun `time reads keyflux_clip_days`() { assertEquals(7L * 86400000, PreferencesManager.resolveClipboardTextTime(mapOf("keyflux_clip_days" to 7))) }

    @Test fun `time returns -1 when days is 0 (forever)`() { assertEquals(-1L, PreferencesManager.resolveClipboardTextTime(mapOf("keyflux_clip_days" to 0))) }

    @Test fun `time returns -1 when days is negative`() { assertEquals(-1L, PreferencesManager.resolveClipboardTextTime(mapOf("keyflux_clip_days" to -5))) }

    @Test fun `time defaults when key is missing`() { assertEquals(threeDaysMillis, PreferencesManager.resolveClipboardTextTime(mapOf("other_key" to 99))) }

    @Test fun `time of 1 day`() { assertEquals(1L * 86400000, PreferencesManager.resolveClipboardTextTime(mapOf("keyflux_clip_days" to 1))) }
}
