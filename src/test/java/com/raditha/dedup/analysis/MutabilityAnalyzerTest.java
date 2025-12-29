package com.raditha.dedup.analysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for MutabilityAnalyzer.
 * Tests type mutability detection for test isolation.
 */
class MutabilityAnalyzerTest {

    private MutabilityAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new MutabilityAnalyzer();
    }

    @Test
    void testIsSafeToPromote_String() {
        assertTrue(analyzer.isSafeToPromote("String"),
                "String is immutable and should be safe to promote");
    }

    @Test
    void testIsSafeToPromote_Integer() {
        assertTrue(analyzer.isSafeToPromote("Integer"),
                "Integer wrapper is immutable and should be safe");
    }

    @Test
    void testIsSafeToPromote_LocalDate() {
        assertTrue(analyzer.isSafeToPromote("LocalDate"),
                "LocalDate is immutable and should be safe");
    }

    @Test
    void testIsSafeToPromote_User() {
        assertFalse(analyzer.isSafeToPromote("User"),
                "User is a mutable domain object and should not be safe");
    }

    @Test
    void testIsSafeToPromote_List() {
        assertFalse(analyzer.isSafeToPromote("List"),
                "List is mutable and should not be safe");
    }

    @Test
    void testIsSafeToPromote_MockType() {
        assertTrue(analyzer.isSafeToPromote("UserMock"),
                "Mock objects should be safe to promote");
        assertTrue(analyzer.isSafeToPromote("CustomerSpy"),
                "Spy objects should be safe to promote");
    }

    @Test
    void testIsSafeToPromote_ServiceType() {
        assertTrue(analyzer.isSafeToPromote("UserService"),
                "Service objects should be safe to promote");
        assertTrue(analyzer.isSafeToPromote("DatabaseHelper"),
                "Helper objects should be safe to promote");
    }

    @Test
    void testGetMutabilityWarning_MutableType() {
        String warning = analyzer.getMutabilityWarning("Customer");
        assertNotNull(warning, "Should return warning for mutable type");
        assertTrue(warning.contains("Customer"),
                "Warning should mention the type name");
        assertTrue(warning.contains("mutable"),
                "Warning should mention mutability");
    }

    @Test
    void testGetMutabilityWarning_ImmutableType() {
        String warning = analyzer.getMutabilityWarning("String");
        assertNull(warning, "Should return null for immutable types");
    }

    @Test
    void testIsSafeToPromote_Database() {
        assertTrue(analyzer.isSafeToPromote("Database"),
                "Database test infrastructure should be safe");
    }

    @Test
    void testIsSafeToPromote_ArrayList() {
        assertFalse(analyzer.isSafeToPromote("ArrayList"),
                "ArrayList is mutable and should not be safe");
    }

    @Test
    void testIsSafeToPromote_ImmutableList() {
        assertTrue(analyzer.isSafeToPromote("ImmutableList"),
                "ImmutableList should be safe");
    }
}
