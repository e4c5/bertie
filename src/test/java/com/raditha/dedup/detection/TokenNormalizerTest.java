package com.raditha.dedup.detection;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.Token;
import com.raditha.dedup.model.TokenType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TokenNormalizer.
 * Verifies semantic preservation and normalization rules.
 */
class TokenNormalizerTest {

    private TokenNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new TokenNormalizer();
    }

    @Test
    void testMethodNamePreservation() {
        // setActive and setDeleted should produce different tokens
        Statement stmt1 = parseStatement("user.setActive(true);");
        Statement stmt2 = parseStatement("user.setDeleted(true);");

        List<Token> tokens1 = normalizer.normalizeStatement(stmt1);
        List<Token> tokens2 = normalizer.normalizeStatement(stmt2);

        // Find METHOD_CALL tokens
        Token methodCall1 = findTokenByType(tokens1, TokenType.METHOD_CALL);
        Token methodCall2 = findTokenByType(tokens2, TokenType.METHOD_CALL);

        assertNotNull(methodCall1);
        assertNotNull(methodCall2);
        // UPDATED: Normalized value should be generic "METHOD_CALL" to allow alignment
        assertEquals("METHOD_CALL", methodCall1.normalizedValue());
        assertEquals("METHOD_CALL", methodCall2.normalizedValue());

        // UPDATED: They SHOULD match semantically for alignment purposes
        assertTrue(methodCall1.semanticallyMatches(methodCall2),
                "setActive and setDeleted SHOULD match semantically for alignment");

        // But original values must differ
        assertNotEquals(methodCall1.originalValue(), methodCall2.originalValue());
    }

    @Test
    void testVariableNormalization() {
        // Different variable names should produce same VAR token
        Statement stmt1 = parseStatement("userId = \"123\";");
        Statement stmt2 = parseStatement("customerId = \"456\";");

        List<Token> tokens1 = normalizer.normalizeStatement(stmt1);
        List<Token> tokens2 = normalizer.normalizeStatement(stmt2);

        // Both should have VAR tokens with same normalized value
        List<Token> vars1 = findAllTokensByType(tokens1, TokenType.VAR);
        List<Token> vars2 = findAllTokensByType(tokens2, TokenType.VAR);

        assertFalse(vars1.isEmpty());
        assertFalse(vars2.isEmpty());

        // VAR tokens should match semantically
        Token var1 = vars1.getFirst();
        Token var2 = vars2.getFirst();
        assertEquals("VAR", var1.normalizedValue());
        assertEquals("VAR", var2.normalizedValue());
        assertTrue(var1.semanticallyMatches(var2),
                "Variable tokens should match semantically");
    }

    @Test
    void testLiteralNormalization() {
        // Different string literals should normalize to same type BUT different values
        Statement stmt1 = parseStatement("status = \"PENDING\";");
        Statement stmt2 = parseStatement("status = \"APPROVED\";");

        List<Token> tokens1 = normalizer.normalizeStatement(stmt1);
        List<Token> tokens2 = normalizer.normalizeStatement(stmt2);

        Token lit1 = findTokenByType(tokens1, TokenType.STRING_LIT);
        Token lit2 = findTokenByType(tokens2, TokenType.STRING_LIT);

        assertNotNull(lit1);
        assertNotNull(lit2);
        assertEquals("STRING_LIT", lit1.normalizedValue());
        assertEquals("STRING_LIT", lit2.normalizedValue());
        // UPDATED: After fix, different values SHOULD match for structural alignment
        // The VariationTracker is responsible for checking value equality
        assertTrue(lit1.semanticallyMatches(lit2),
                "String literals with different values SHOULD match semantically (structural)");
    }

    @Test
    void testTypePreservation() {
        // User and Customer are different types → preserve difference
        Statement stmt1 = parseStatement("User user = new User();");
        Statement stmt2 = parseStatement("Customer customer = new Customer();");

        List<Token> tokens1 = normalizer.normalizeStatement(stmt1);
        List<Token> tokens2 = normalizer.normalizeStatement(stmt2);

        List<Token> types1 = findAllTokensByType(tokens1, TokenType.TYPE);
        List<Token> types2 = findAllTokensByType(tokens2, TokenType.TYPE);

        assertFalse(types1.isEmpty());
        assertFalse(types2.isEmpty());

        // Should have generic TYPE tokens
        assertEquals("TYPE", types1.get(0).normalizedValue());
        assertEquals("TYPE", types2.get(0).normalizedValue());

        // These SHOULD match semantically for alignment
        Token userType = types1.get(0);
        Token customerType = types2.get(0);

        assertTrue(userType.semanticallyMatches(customerType),
                "User and Customer types should match semantically for alignment");

        // But original values differ
        assertNotEquals(userType.originalValue(), customerType.originalValue());
    }

    @Test
    void testControlFlowDetection() {
        Statement ifStmt = parseStatement("if (x > 0) { return true; }");
        Statement forStmt = parseStatement("for (int i = 0; i < 10; i++) { }");

        List<Token> ifTokens = normalizer.normalizeStatement(ifStmt);
        List<Token> forTokens = normalizer.normalizeStatement(forStmt);

        Token ifToken = findTokenByType(ifTokens, TokenType.CONTROL_FLOW);
        Token forToken = findTokenByType(forTokens, TokenType.CONTROL_FLOW);

        assertNotNull(ifToken);
        assertNotNull(forToken);
        assertEquals("CONTROL_FLOW(if)", ifToken.normalizedValue());
        assertEquals("CONTROL_FLOW(for)", forToken.normalizedValue());
    }

    @Test
    void testAssertionDetection() {
        Statement stmt = parseStatement("assertEquals(expected, actual);");

        List<Token> tokens = normalizer.normalizeStatement(stmt);

        Token assertToken = findTokenByType(tokens, TokenType.ASSERT);
        assertNotNull(assertToken, "Should detect assertEquals as assertion");
        assertTrue(assertToken.normalizedValue().contains("assertEquals"));
    }

    @Test
    void testMockitoDetection() {
        Statement stmt = parseStatement("when(mock.foo()).thenReturn(bar);");

        List<Token> tokens = normalizer.normalizeStatement(stmt);

        // 'when' is a Mockito method
        List<Token> mockTokens = findAllTokensByType(tokens, TokenType.MOCK);
        assertFalse(mockTokens.isEmpty(), "Should detect Mockito methods");

        // Check for 'when'
        boolean hasWhen = mockTokens.stream()
                .anyMatch(t -> t.normalizedValue().contains("when"));
        assertTrue(hasWhen, "Should detect 'when' as Mockito method");
    }

    @Test
    void testRealWorldDuplicatePattern() {
        // Parse each statement individually, not as a block
        List<Token> tokens1 = normalizer.normalizeStatements(List.of(
                parseStatement("Admission admission = new Admission();"),
                parseStatement("admission.setPatientId(\"P123\");"),
                parseStatement("admission.setHospitalId(\"H456\");"),
                parseStatement("admission.setStatus(\"PENDING\");")));

        List<Token> tokens2 = normalizer.normalizeStatements(List.of(
                parseStatement("Admission admission = new Admission();"),
                parseStatement("admission.setPatientId(\"P999\");"),
                parseStatement("admission.setHospitalId(\"H888\");"),
                parseStatement("admission.setStatus(\"APPROVED\");")));

        // Should have similar structure
        assertFalse(tokens1.isEmpty());
        assertFalse(tokens2.isEmpty());

        // Should have same method calls (setPatientId, setHospitalId, setStatus)
        List<Token> methods1 = findAllTokensByType(tokens1, TokenType.METHOD_CALL);
        List<Token> methods2 = findAllTokensByType(tokens2, TokenType.METHOD_CALL);

        // Should have 3 method calls each (setPatientId, setHospitalId, setStatus)
        assertTrue(methods1.size() >= 3, "Should have at least 3 method calls");
        assertTrue(methods2.size() >= 3, "Should have at least 3 method calls");

        // Check for specific method names in ORIGINAL value, not normalized
        assertTrue(methods1.stream().anyMatch(m -> m.originalValue().contains("setPatientId")));
        assertTrue(methods1.stream().anyMatch(m -> m.originalValue().contains("setHospitalId")));
        assertTrue(methods1.stream().anyMatch(m -> m.originalValue().contains("setStatus")));
    }

    // Helper methods

    private Statement parseStatement(String code) {
        try {
            return StaticJavaParser.parseStatement(code);
        } catch (Exception e) {
            fail("Failed to parse statement: " + code, e);
            return null;
        }
    }

    private Token findTokenByType(List<Token> tokens, TokenType type) {
        return tokens.stream()
                .filter(t -> t.type() == type)
                .findFirst()
                .orElse(null);
    }

    private List<Token> findAllTokensByType(List<Token> tokens, TokenType type) {
        return tokens.stream()
                .filter(t -> t.type() == type)
                .toList();
    }
}
