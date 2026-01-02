package com.raditha.dedup.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Token.semanticallyMatches() behavior.
 * 
 * CRITICAL: This tests the fix where literals compare ORIGINAL values
 * while semantic tokens compare NORMALIZED values.
 * 
 * Before fix: All literals with same type matched (enabled similarity but broke
 * variation detection)
 * After fix: Literals match only if original values match (enables both
 * similarity AND variation detection)
 */
class TokenSemanticsTest {

    @Test
    void testStringLiterals_DifferentValues_ShouldNotMatch() {
        // KEY TEST: Different string literals should NOT match semantically
        Token token1 = new Token(TokenType.STRING_LIT, "STRING_LIT", "\"John\"");
        Token token2 = new Token(TokenType.STRING_LIT, "STRING_LIT", "\"Jane\"");

        assertFalse(token1.semanticallyMatches(token2),
                "String literals with different values should NOT match");
    }

    @Test
    void testStringLiterals_SameValue_ShouldMatch() {
        Token token1 = new Token(TokenType.STRING_LIT, "STRING_LIT", "\"John\"");
        Token token2 = new Token(TokenType.STRING_LIT, "STRING_LIT", "\"John\"");

        assertTrue(token1.semanticallyMatches(token2),
                "String literals with same value should match");
    }

    @Test
    void testIntLiterals_DifferentValues_ShouldNotMatch() {
        Token token1 = new Token(TokenType.INT_LIT, "INT_LIT", "25");
        Token token2 = new Token(TokenType.INT_LIT, "INT_LIT", "30");

        assertFalse(token1.semanticallyMatches(token2),
                "Integer literals with different values should NOT match");
    }

    @Test
    void testIntLiterals_SameValue_ShouldMatch() {
        Token token1 = new Token(TokenType.INT_LIT, "INT_LIT", "25");
        Token token2 = new Token(TokenType.INT_LIT, "INT_LIT", "25");

        assertTrue(token1.semanticallyMatches(token2),
                "Integer literals with same value should match");
    }

    @Test
    void testBooleanLiterals_DifferentValues_ShouldNotMatch() {
        Token token1 = new Token(TokenType.BOOLEAN_LIT, "BOOLEAN_LIT", "true");
        Token token2 = new Token(TokenType.BOOLEAN_LIT, "BOOLEAN_LIT", "false");

        assertFalse(token1.semanticallyMatches(token2),
                "Boolean literals with different values should NOT match");
    }

    @Test
    void testLongLiterals_DifferentValues_ShouldNotMatch() {
        Token token1 = new Token(TokenType.LONG_LIT, "LONG_LIT", "5000L");
        Token token2 = new Token(TokenType.LONG_LIT, "LONG_LIT", "10000L");

        assertFalse(token1.semanticallyMatches(token2),
                "Long literals with different values should NOT match");
    }

    @Test
    void testVariables_DifferentNames_ShouldMatch() {
        // Semantic tokens: Compare NORMALIZED values
        Token token1 = new Token(TokenType.VAR, "VAR", "user");
        Token token2 = new Token(TokenType.VAR, "VAR", "customer");

        assertTrue(token1.semanticallyMatches(token2),
                "Variables with different names but same normalized value should match");
    }

    @Test
    void testMethodCalls_SameName_ShouldMatch() {
        // Semantic tokens: Compare NORMALIZED values
        Token token1 = new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "userRepo.save(user)");
        Token token2 = new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "customerRepo.save(customer)");

        assertTrue(token1.semanticallyMatches(token2),
                "Method calls with same name but different scopes should match");
    }

    @Test
    void testMethodCalls_DifferentNames_ShouldNotMatch() {
        Token token1 = new Token(TokenType.METHOD_CALL, "METHOD_CALL(save)", "save(user)");
        Token token2 = new Token(TokenType.METHOD_CALL, "METHOD_CALL(delete)", "delete(user)");

        assertFalse(token1.semanticallyMatches(token2),
                "Method calls with different names should NOT match");
    }

    @Test
    void testTypes_SameName_ShouldMatch() {
        // Semantic tokens: Compare NORMALIZED values
        Token token1 = new Token(TokenType.TYPE, "TYPE(User)", "User");
        Token token2 = new Token(TokenType.TYPE, "TYPE(User)", "User");

        assertTrue(token1.semanticallyMatches(token2),
                "Types with same name should match");
    }

    @Test
    void testTypes_DifferentNames_ShouldNotMatch() {
        Token token1 = new Token(TokenType.TYPE, "TYPE(User)", "User");
        Token token2 = new Token(TokenType.TYPE, "TYPE(Customer)", "Customer");

        assertFalse(token1.semanticallyMatches(token2),
                "Types with different names should NOT match");
    }

    @Test
    void testDifferentTokenTypes_ShouldNotMatch() {
        Token token1 = new Token(TokenType.STRING_LIT, "STRING_LIT", "\"John\"");
        Token token2 = new Token(TokenType.VAR, "VAR", "John");

        assertFalse(token1.semanticallyMatches(token2),
                "Tokens of different types should never match");
    }

    @Test
    void testNullToken_ShouldNotMatch() {
        Token token1 = new Token(TokenType.STRING_LIT, "STRING_LIT", "\"John\"");

        assertFalse(token1.semanticallyMatches(null),
                "Token should not match null");
    }

    /**
     * Integration test: Verifies the complete flow
     * This is the actual use case that was broken before the fix
     */
    @Test
    void testRealWorldScenario_UserSetNameVariation() {
        // Before fix: These would all match (broken!)
        // After fix: Only identical values match (correct!)

        Token johnLiteral = new Token(TokenType.STRING_LIT, "STRING_LIT", "\"John\"");
        Token janeLiteral = new Token(TokenType.STRING_LIT, "STRING_LIT", "\"Jane\"");
        Token adminLiteral = new Token(TokenType.STRING_LIT, "STRING_LIT", "\"Admin\"");

        // All should be different
        assertFalse(johnLiteral.semanticallyMatches(janeLiteral));
        assertFalse(johnLiteral.semanticallyMatches(adminLiteral));
        assertFalse(janeLiteral.semanticallyMatches(adminLiteral));

        // But the method call tokens should match
        Token setNameCall1 = new Token(TokenType.METHOD_CALL, "METHOD_CALL(setName)", "user.setName(\"John\")");
        Token setNameCall2 = new Token(TokenType.METHOD_CALL, "METHOD_CALL(setName)", "user.setName(\"Jane\")");

        assertTrue(setNameCall1.semanticallyMatches(setNameCall2),
                "setName method calls should match regardless of arguments");
    }
}
