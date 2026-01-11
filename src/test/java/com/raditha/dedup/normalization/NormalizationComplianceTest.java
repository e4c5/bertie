package com.raditha.dedup.normalization;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests compliance with the design document regarding token normalization.
 * Specifically checks that method names are PRESERVED during fuzzy normalization.
 */
class NormalizationComplianceTest {

    private ASTNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new ASTNormalizer();
    }

    private Statement stmt(String code) { return StaticJavaParser.parseStatement(code); }

    @Test
    void testMethodNamesArePreserved_DesignRequirement() {
        // Design Doc: "user.setActive(true) -> [VAR, METHOD_CALL(setActive), LITERAL]"
        Statement s1 = stmt("user.setActive(true);");
        Statement s2 = stmt("user.setDeleted(true);");

        var fuzzy1 = normalizer.normalizeFuzzy(List.of(s1));
        var fuzzy2 = normalizer.normalizeFuzzy(List.of(s2));

        String norm1 = fuzzy1.get(0).normalized().toString();
        String norm2 = fuzzy2.get(0).normalized().toString();

        // Check structure is normalized
        assertTrue(norm1.contains("VAR"), "Variable 'user' should be normalized to VAR");
        assertTrue(norm1.contains("BOOL_LIT") || norm1.contains("true"), "Literal should be normalized/handled");

        // KEY CHECK: Method names must be distinct
        // Currently, the implementation fails this (both become "METHOD")
        // We assert the CORRECT behavior here to demonstrate the failure (or fix)

        // This assertion documents what SHOULD happen according to design
        // If the code is buggy, this might fail (or we assert the failure if we want to prove it's broken)
        // Let's assert the correct behavior.
        assertTrue(norm1.contains("setActive"), "Method name 'setActive' should be preserved in: " + norm1);
        assertTrue(norm2.contains("setDeleted"), "Method name 'setDeleted' should be preserved in: " + norm2);

        assertNotEquals(norm1, norm2, "Semantically different methods should have different normalized forms");
    }

    @Test
    void testVariableNamesAreAnonymized() {
        Statement s = stmt("myVar.save(123);");
        var fuzzy = normalizer.normalizeFuzzy(List.of(s));
        String norm = fuzzy.get(0).normalized().toString();

        assertTrue(norm.contains("VAR"), "Variable 'myVar' should be normalized to VAR");
        assertFalse(norm.contains("myVar"), "Original variable name should not appear");
        assertTrue(norm.contains("save"), "Method name 'save' should be preserved");
    }
}
