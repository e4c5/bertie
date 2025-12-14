package com.raditha.dedup.analysis;

import com.raditha.dedup.model.TypeCompatibility;
import com.raditha.dedup.model.Variation;
import com.raditha.dedup.model.VariationAnalysis;
import com.raditha.dedup.model.VariationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TypeAnalyzer.
 */
class TypeAnalyzerTest {

    private TypeAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new TypeAnalyzer();
    }

    @Test
    void testCompatibleStringLiterals() {
        List<Variation> variations = List.of(
                new Variation(VariationType.LITERAL, 0, 0, "\"John\"", "\"Jane\"", "String"),
                new Variation(VariationType.LITERAL, 1, 1, "\"test@example.com\"", "\"user@example.com\"", "String"));

        VariationAnalysis analysis = new VariationAnalysis(variations, false);
        TypeCompatibility compatibility = analyzer.analyzeTypeCompatibility(analysis);

        assertTrue(compatibility.allVariationsTypeSafe());
        assertTrue(compatibility.parameterTypes().containsValue("String"));
    }

    @Test
    void testCompatibleNumericLiterals() {
        List<Variation> variations = List.of(
                new Variation(VariationType.LITERAL, 0, 0, "1", "2", "int"),
                new Variation(VariationType.LITERAL, 1, 1, "5", "10", "int"));

        VariationAnalysis analysis = new VariationAnalysis(variations, false);
        TypeCompatibility compatibility = analyzer.analyzeTypeCompatibility(analysis);

        assertTrue(compatibility.allVariationsTypeSafe());
        assertTrue(compatibility.parameterTypes().containsValue("int"));
    }

    @Test
    void testIncompatibleTypesFallbackToObject() {
        List<Variation> variations = List.of(
                new Variation(VariationType.LITERAL, 0, 0, "\"text\"", "123", "String"));

        VariationAnalysis analysis = new VariationAnalysis(variations, false);
        TypeCompatibility compatibility = analyzer.analyzeTypeCompatibility(analysis);

        // Should have type info even if inconsistent
        assertNotNull(compatibility.parameterTypes());
    }

    @Test
    void testControlFlowVariations() {
        List<Variation> variations = List.of(
                new Variation(VariationType.CONTROL_FLOW, 0, 0, "if", "while", null));

        VariationAnalysis analysis = new VariationAnalysis(variations, false);
        TypeCompatibility compatibility = analyzer.analyzeTypeCompatibility(analysis);

        assertFalse(compatibility.allVariationsTypeSafe());
        assertFalse(compatibility.warnings().isEmpty());
    }

    @Test
    void testNoVariations() {
        VariationAnalysis analysis = new VariationAnalysis(List.of(), false);
        TypeCompatibility compatibility = analyzer.analyzeTypeCompatibility(analysis);

        assertTrue(compatibility.allVariationsTypeSafe());
        assertTrue(compatibility.parameterTypes().isEmpty());
    }
}
