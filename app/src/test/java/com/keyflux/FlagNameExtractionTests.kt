package com.keyflux

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for FlagsOverrideManager.extractFlagName() via companion object.
 */
class FlagNameExtractionTests {

    // --- Test classes simulating obfuscated Gboard flag objects ---

    class ObfuscatedFlagA {
        @JvmField var a: String = "enable_smart_compose"
    }

    class ObfuscatedFlagB {
        @JvmField var b: String = "enable_grammar_checker"
    }

    class EmptyAWithFallback {
        @JvmField var a: String = ""
        @JvmField var name: String = "enable_multilingual_typing"
    }

    class NoStringFields {
        @JvmField var count: Int = 42
    }

    class NullAField {
        @JvmField var a: String? = null
    }

    open class ParentFlag {
        @JvmField var name: String = "parent_flag_name"
    }
    class ChildFlagEmptyA : ParentFlag() {
        @JvmField var a: String = ""
    }

    // --- Tests ---

    @Test fun `extracts flag name from field a`() {
        assertEquals("enable_smart_compose", FlagsOverrideManager.extractFlagName(ObfuscatedFlagA()))
    }

    @Test fun `falls back to first String field when a is missing`() {
        assertEquals("enable_grammar_checker", FlagsOverrideManager.extractFlagName(ObfuscatedFlagB()))
    }

    @Test fun `falls back when a is empty`() {
        assertEquals("enable_multilingual_typing", FlagsOverrideManager.extractFlagName(EmptyAWithFallback()))
    }

    @Test fun `returns null when no String fields exist`() {
        assertNull(FlagsOverrideManager.extractFlagName(NoStringFields()))
    }

    @Test fun `falls back through class hierarchy for String field`() {
        assertEquals("parent_flag_name", FlagsOverrideManager.extractFlagName(ChildFlagEmptyA()))
    }

    @Test fun `handles null field a by searching hierarchy`() {
        assertNull(FlagsOverrideManager.extractFlagName(NullAField()))
    }
}
