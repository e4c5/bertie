package com.raditha.dedup.analysis;

import java.util.Set;

/**
 * Analyzes whether types are safe to promote to instance fields in test
 * classes.
 * 
 * Gap 6 FIX: Detects mutable types that could break test isolation.
 */
public class MutabilityAnalyzer {

    private static final Set<String> IMMUTABLE_TYPES = Set.of(
            // Primitives and wrappers
            "String", "Integer", "int", "Long", "long", "Double", "double",
            "Float", "float", "Boolean", "boolean", "Character", "char",
            "Byte", "byte", "Short", "short",

            // Java immutable classes
            "BigDecimal", "BigInteger", "LocalDate", "LocalDateTime",
            "LocalTime", "Instant", "Duration", "Period", "ZonedDateTime",
            "OffsetDateTime", "UUID", "URI", "URL", "Path",

            // Immutable collections
            "ImmutableList", "ImmutableSet", "ImmutableMap",

            // Test infrastructure (safe for test code)
            "Database", "Connection");

    private static final Set<String> MOCK_INDICATORS = Set.of(
            "Mock", "Spy", "InjectMocks", "Captor");

    /**
     * Check if a type is safe to promote to an instance field in a test class.
     * Safe types are immutable, mocks, or stateless test helpers.
     *
     * @param typeName The type name to check
     * @return true if safe to promote
     */
    public boolean isSafeToPromote(String typeName) {
        if (isImmutableType(typeName) || isMockType(typeName)) {
            return true;
        }

        // Allow test infrastructure patterns
        String baseType = stripGenerics(typeName);
        return (baseType.endsWith("Service") || baseType.endsWith("Mock") ||
                baseType.endsWith("Stub") || baseType.endsWith("Test") ||
                baseType.endsWith("Helper"));
    }

    /**
     * Check if a type is known to be immutable.
     *
     * @param typeName The type name
     * @return true if immutable
     */
    public boolean isImmutableType(String typeName) {
        return IMMUTABLE_TYPES.contains(stripGenerics(typeName));
    }

    /**
     * Check if a type is a mock or spy based on naming conventions.
     *
     * @param typeName The type name
     * @return true if it appears to be a mock
     */
    public boolean isMockType(String typeName) {
        String baseType = stripGenerics(typeName);
        for (String indicator : MOCK_INDICATORS) {
            if (baseType.contains(indicator)) {
                return true;
            }
        }
        return false;
    }

    private String stripGenerics(String typeName) {
        int genericStart = typeName.indexOf('<');
        return (genericStart > 0) ? typeName.substring(0, genericStart) : typeName;
    }

    /**
     * Get a warning message if the type is mutable and unsafe to promote.
     *
     * @param typeName The type name
     * @return Warning message or null if safe
     */
    public String getMutabilityWarning(String typeName) {
        if (isSafeToPromote(typeName)) {
            return null;
        }
        return String.format(
                "Type '%s' appears to be mutable. Promoting to instance field may cause test isolation issues.",
                typeName);
    }
}
