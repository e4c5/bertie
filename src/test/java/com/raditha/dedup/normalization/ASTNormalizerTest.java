package com.raditha.dedup.normalization;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ASTNormalizerTest {

    private ASTNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new ASTNormalizer();
    }

    @Test
    void testNormalizeStringLiteral() {
        Statement stmt = parseStatement("user.setName(\"Alice\");");
        List<NormalizedNode> normalized = normalizer.normalize(List.of(stmt));

        assertEquals(1, normalized.size());
        assertEquals("user.setName(STRING_LIT);", normalized.get(0).normalized().toString());
        assertEquals("user.setName(\"Alice\");", normalized.get(0).original().toString());
    }

    @Test
    void testNormalizeIntegerLiteral() {
        Statement stmt = parseStatement("user.setAge(25);");
        List<NormalizedNode> normalized = normalizer.normalize(List.of(stmt));

        assertEquals("user.setAge(INT_LIT);", normalized.get(0).normalized().toString());
        assertEquals("user.setAge(25);", normalized.get(0).original().toString());
    }

    @Test
    void testNormalizeBooleanLiteral() {
        Statement stmt = parseStatement("user.setActive(true);");
        List<NormalizedNode> normalized = normalizer.normalize(List.of(stmt));

        assertEquals("user.setActive(BOOL_LIT);", normalized.get(0).normalized().toString());
        assertEquals("user.setActive(true);", normalized.get(0).original().toString());
    }

    @Test
    void testNormalizeConcatenation() {
        Statement stmt = parseStatement("logger.info(\"Starting: \" + userName);");
        List<NormalizedNode> normalized = normalizer.normalize(List.of(stmt));

        String result = normalized.get(0).normalized().toString();

        // Should preserve BinaryExpr structure
        assertTrue(result.contains("STRING_LIT"));
        assertTrue(result.contains("userName"));
        assertTrue(result.contains("+"));

        // Original should be unchanged
        assertEquals("logger.info(\"Starting: \" + userName);", normalized.get(0).original().toString());
    }

    @Test
    void testNormalizeMultipleLiterals() {
        Statement stmt = parseStatement("result = \"Value: \" + 42 + \" active: \" + true;");
        List<NormalizedNode> normalized = normalizer.normalize(List.of(stmt));

        String result = normalized.get(0).normalized().toString();

        // All literals should be replaced
        assertTrue(result.contains("STRING_LIT"));
        assertTrue(result.contains("INT_LIT"));
        assertTrue(result.contains("BOOL_LIT"));
    }

    @Test
    void testNormalizeMethodCall() {
        Statement stmt = parseStatement("user.getName();");
        List<NormalizedNode> normalized = normalizer.normalize(List.of(stmt));

        // No literals, should remain unchanged
        assertEquals("user.getName();", normalized.get(0).normalized().toString());
        assertEquals("user.getName();", normalized.get(0).original().toString());
    }

    @Test
    void testStructurallyEquals() {
        Statement stmt1 = parseStatement("user.setName(\"Alice\");");
        Statement stmt2 = parseStatement("user.setName(\"Bob\");");

        List<NormalizedNode> norm1 = normalizer.normalize(List.of(stmt1));
        List<NormalizedNode> norm2 = normalizer.normalize(List.of(stmt2));

        // Different literals but same structure
        assertTrue(norm1.get(0).structurallyEquals(norm2.get(0)));
    }

    @Test
    void testStructurallyNotEquals() {
        Statement stmt1 = parseStatement("user.setName(\"Alice\");");
        Statement stmt2 = parseStatement("user.setAge(25);");

        List<NormalizedNode> norm1 = normalizer.normalize(List.of(stmt1));
        List<NormalizedNode> norm2 = normalizer.normalize(List.of(stmt2));

        // Different methods
        assertFalse(norm1.get(0).structurallyEquals(norm2.get(0)));
    }

    @Test
    void testNormalizeMultipleStatements() {
        Statement stmt1 = parseStatement("user.setName(\"Alice\");");
        Statement stmt2 = parseStatement("user.setAge(25);");
        Statement stmt3 = parseStatement("user.setActive(true);");

        List<NormalizedNode> normalized = normalizer.normalize(List.of(stmt1, stmt2, stmt3));

        assertEquals(3, normalized.size());
        assertEquals("user.setName(STRING_LIT);", normalized.get(0).normalized().toString());
        assertEquals("user.setAge(INT_LIT);", normalized.get(1).normalized().toString());
        assertEquals("user.setActive(BOOL_LIT);", normalized.get(2).normalized().toString());
    }

    private Statement parseStatement(String code) {
        return StaticJavaParser.parseStatement(code);
    }
}
