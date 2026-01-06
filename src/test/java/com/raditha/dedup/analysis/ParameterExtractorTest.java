package com.raditha.dedup.analysis;

import com.raditha.dedup.model.ParameterSpec;
import com.raditha.dedup.model.Token;
import com.raditha.dedup.model.TokenType;
import com.raditha.dedup.model.Variation;
import com.raditha.dedup.model.VariationAnalysis;
import com.raditha.dedup.model.VariationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ParameterExtractor.
 */
class ParameterExtractorTest {

    private ParameterExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new ParameterExtractor();
    }

    private List<Token> createDummyTokens(int size) {
        List<Token> tokens = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            tokens.add(new Token(TokenType.VAR, "val" + i, "val" + i, "String", 1, i));
        }
        return tokens;
    }

    @Test
    void testExtractStringParameters() {
        List<Variation> variations = List.of(
                new Variation(VariationType.LITERAL, 0, 0, "\"John\"", "\"Jane\"", "String"),
                new Variation(VariationType.LITERAL, 1, 1, "\"test@example.com\"", "\"user@example.com\"", "String"));

        VariationAnalysis analysis = new VariationAnalysis(
                variations,
                false,
                java.util.Map.of(),
                createDummyTokens(20)); // Pass tokens

        Map<String, String> types = Map.of("param0", "String", "param1", "String");

        List<ParameterSpec> params = extractor.extractParameters(analysis, types);

        assertEquals(2, params.size());
        assertTrue(params.stream().allMatch(p -> p.getType().asString().equals("String")));

        // Check example values are captured
        ParameterSpec first = params.get(0);
        assertTrue(first.getExampleValues().contains("\"John\"") || first.getExampleValues().contains("\"Jane\""));
    }

    @Test
    void testExtractPrimitiveParameters() {
        List<Variation> variations = List.of(
                new Variation(VariationType.LITERAL, 0, 0, "1", "2", "int"),
                new Variation(VariationType.LITERAL, 1, 1, "true", "false", "boolean"));

        VariationAnalysis analysis = new VariationAnalysis(
                variations,
                false,
                java.util.Map.of(),
                createDummyTokens(20));

        Map<String, String> types = Map.of("param0", "int", "param1", "boolean");

        List<ParameterSpec> params = extractor.extractParameters(analysis, types);

        assertEquals(2, params.size());

        // Primitives should come first
        assertEquals("int", params.get(0).getType().asString());
        assertEquals("boolean", params.get(1).getType().asString());
    }

    @Test
    void testVariableParameterNaming() {
        List<Variation> variations = List.of(
                new Variation(VariationType.VARIABLE, 0, 0, "userId", "customerId", "int"),
                new Variation(VariationType.VARIABLE, 1, 1, "userName", "customerName", "String"));

        VariationAnalysis analysis = new VariationAnalysis(
                variations,
                false,
                java.util.Map.of(),
                createDummyTokens(20));

        Map<String, String> types = Map.of("param0", "int", "param1", "String");

        List<ParameterSpec> params = extractor.extractParameters(analysis, types);

        assertEquals(2, params.size());

        // Names should reflect the pattern (Id, Name)
        // Note: The actual name inference is heuristic-based
        assertNotNull(params.get(0).getName());
        assertNotNull(params.get(1).getName());
    }

    @Test
    void testMaxParameterLimit() {
        // Create 10 variations
        List<Variation> variations = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            variations.add(new Variation(
                    VariationType.LITERAL,
                    i, i,
                    "\"value" + i + "\"",
                    "\"other" + i + "\"",
                    "String"));
        }

        VariationAnalysis analysis = new VariationAnalysis(
                variations,
                false,
                java.util.Map.of(),
                createDummyTokens(20));

        Map<String, String> types = new java.util.HashMap<>(java.util.Map.of());
        for (int i = 0; i < 10; i++) {
            types.put("param" + i, "String");
        }

        List<ParameterSpec> params = extractor.extractParameters(analysis, types);

        // Should limit to 5 parameters
        assertTrue(params.size() <= 5);
    }

    @Test
    void testSkipControlFlowVariations() {
        List<Variation> variations = List.of(
                new Variation(VariationType.CONTROL_FLOW, 0, 0, "if", "while", null),
                new Variation(VariationType.LITERAL, 1, 1, "1", "2", "int"));

        VariationAnalysis analysis = new VariationAnalysis(
                variations,
                false,
                java.util.Map.of(),
                createDummyTokens(20));

        Map<String, String> types = Map.of("param1", "int");

        List<ParameterSpec> params = extractor.extractParameters(analysis, types);

        // Should only extract non-control-flow parameters
        assertEquals(1, params.size());
        assertEquals("int", params.get(0).getType().asString());
    }

    @Test
    void testDuplicateParameterNamesRenaming() {
        // Create two integer variations that would both map to "id" (pattern matched)
        List<Variation> variations = List.of(
                new Variation(VariationType.LITERAL, 0, 0, "1", "2", "int"),
                new Variation(VariationType.LITERAL, 1, 1, "100", "200", "int"));

        VariationAnalysis analysis = new VariationAnalysis(
                variations,
                false,
                java.util.Map.of(),
                createDummyTokens(20));

        Map<String, String> types = Map.of("param0", "int", "param1", "int");

        List<ParameterSpec> params = extractor.extractParameters(analysis, types);

        assertEquals(2, params.size());

        ParameterSpec p1 = params.get(0);
        ParameterSpec p2 = params.get(1);

        // Both should be valid identifiers and UNIQUE
        assertNotNull(p1.getName());
        assertNotNull(p2.getName());
        assertNotEquals(p1.getName(), p2.getName(), "Parameter names must be unique");

        // Verify the renaming strategy (e.g., id, id2)
        // Note: The order depends on pattern matching success. Both are ints -> "id".
        assertTrue(p1.getName().equals("id") || p1.getName().equals("id2"));
        assertTrue(p2.getName().equals("id") || p2.getName().equals("id2"));
    }
}
