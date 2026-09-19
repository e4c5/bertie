package com.raditha.dedup.refactoring;

import com.raditha.dedup.analysis.ControlFlowVariationAnalyzer;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.RefactoringStrategy;
import com.raditha.dedup.model.SimilarityResult;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.model.VariationAnalysis;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.raditha.dedup.refactoring.SafetyValidatorSideEffectTest.cluster;
import static com.raditha.dedup.refactoring.SafetyValidatorSideEffectTest.sequence;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SafetyValidatorControlFlowTest {

    private SafetyValidator validator;
    private RefactoringRecommendation recommendation;
    private final ControlFlowVariationAnalyzer analyzer = new ControlFlowVariationAnalyzer();

    private static final String IF_A = """
                int n = items.size();
                if (n > 10) {
                    items.clear();
                }
                items.add("a");
            """;
    private static final String IF_B_DIFFERENT_CONDITION = """
                int n = items.size();
                if (n > 20) {
                    items.clear();
                }
                items.add("b");
            """;
    private static final String IF_B_WITH_ELSE = """
                int n = items.size();
                if (n > 10) {
                    items.clear();
                } else {
                    items.remove(0);
                }
                items.add("b");
            """;
    private static final String IF_B_NESTED_RETURN = """
                int n = items.size();
                if (n > 10) {
                    return;
                }
                items.add("b");
            """;

    @BeforeEach
    void setUp() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/analyzer-tests.yml"));
        validator = new SafetyValidator();
        recommendation = mock(RefactoringRecommendation.class);
        when(recommendation.getSuggestedMethodName()).thenReturn("helper");
        when(recommendation.getSuggestedParameters()).thenReturn(List.of());
        when(recommendation.getStrategy()).thenReturn(RefactoringStrategy.EXTRACT_HELPER_METHOD);
    }

    @AfterEach
    void resetSettings() {
        Settings.setProperty("duplication_detector_cli", new HashMap<String, Object>());
    }

    private static void allowParameterizable(boolean allow) {
        Map<String, Object> cli = new HashMap<>();
        cli.put("allow_parameterizable_control_flow", allow);
        Settings.setProperty("duplication_detector_cli", cli);
    }

    @Test
    void identicalControlFlowIsNone() {
        StatementSequence a = sequence(IF_A);
        StatementSequence b = sequence(IF_A.replace("\"a\"", "\"b\""));
        assertEquals(ControlFlowVariationAnalyzer.Kind.NONE, analyzer.analyze(a, b).kind());
        assertTrue(validator.validate(cluster(a, b), recommendation).getErrors().stream()
                .noneMatch(e -> e.startsWith(SafetyValidator.CONTROL_FLOW_ERROR)));
    }

    @Test
    void differingConditionIsParameterizable() {
        assertEquals(ControlFlowVariationAnalyzer.Kind.PARAMETERIZABLE,
                analyzer.analyze(sequence(IF_A), sequence(IF_B_DIFFERENT_CONDITION)).kind());
    }

    @Test
    void extraElseBranchIsUnsafe() {
        assertEquals(ControlFlowVariationAnalyzer.Kind.UNSAFE,
                analyzer.analyze(sequence(IF_A), sequence(IF_B_WITH_ELSE)).kind());
    }

    @Test
    void returnInsteadOfStatementIsUnsafe() {
        assertEquals(ControlFlowVariationAnalyzer.Kind.UNSAFE,
                analyzer.analyze(sequence(IF_A), sequence(IF_B_NESTED_RETURN)).kind());
    }

    @Test
    void loopBoundDifferenceIsParameterizableButUpdateDifferenceIsUnsafe() {
        String base = """
                    for (int i = 0; i < 10; i++) {
                        items.add("x");
                    }
                """;
        assertEquals(ControlFlowVariationAnalyzer.Kind.PARAMETERIZABLE,
                analyzer.analyze(sequence(base), sequence(base.replace("i < 10", "i < limit"))).kind());
        assertEquals(ControlFlowVariationAnalyzer.Kind.UNSAFE,
                analyzer.analyze(sequence(base), sequence(base.replace("i++", "i += 2"))).kind());
    }

    @Test
    void differentCatchTypesAreUnsafe() {
        String base = """
                    try {
                        items.add("x");
                    } catch (RuntimeException e) {
                        items.clear();
                    }
                """;
        assertEquals(ControlFlowVariationAnalyzer.Kind.UNSAFE,
                analyzer.analyze(sequence(base), sequence(base.replace("RuntimeException", "Exception"))).kind());
        assertEquals(ControlFlowVariationAnalyzer.Kind.NONE,
                analyzer.analyze(sequence(base), sequence(base)).kind());
    }

    @Test
    void parameterizableVariationIsBlockingByDefault() {
        SafetyValidator.ValidationResult result =
                validator.validate(cluster(sequence(IF_A), sequence(IF_B_DIFFERENT_CONDITION)), recommendation);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.startsWith(SafetyValidator.CONTROL_FLOW_ERROR)),
                result.getErrors().toString());
        assertTrue(result.getWarnings().stream().noneMatch(w -> w.startsWith(SafetyValidator.CONTROL_FLOW_WARNING)));
    }

    @Test
    void parameterizableVariationIsDowngradedToWarningWhenAllowed() {
        allowParameterizable(true);

        SafetyValidator.ValidationResult result =
                validator.validate(cluster(sequence(IF_A), sequence(IF_B_DIFFERENT_CONDITION)), recommendation);

        assertTrue(result.getErrors().stream().noneMatch(e -> e.startsWith(SafetyValidator.CONTROL_FLOW_ERROR)),
                result.getErrors().toString());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.startsWith(SafetyValidator.CONTROL_FLOW_WARNING)),
                result.getWarnings().toString());
    }

    @Test
    void unsafeVariationStaysBlockingEvenWhenRelaxed() {
        allowParameterizable(true);

        SafetyValidator.ValidationResult result =
                validator.validate(cluster(sequence(IF_A), sequence(IF_B_WITH_ELSE)), recommendation);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.startsWith(SafetyValidator.CONTROL_FLOW_ERROR)));

        result = validator.validate(cluster(sequence(IF_A), sequence(IF_B_NESTED_RETURN)), recommendation);
        assertFalse(result.isValid());
    }

    @Test
    void legacyControlFlowFlagDoesNotOverrideParameterizableClassification() {
        allowParameterizable(true);
        StatementSequence a = sequence(IF_A);
        StatementSequence b = sequence(IF_B_DIFFERENT_CONDITION);
        var cl = cluster(a, b);
        flagControlFlow(cl);

        SafetyValidator.ValidationResult result = validator.validate(cl, recommendation);

        assertTrue(result.getErrors().stream().noneMatch(e -> e.startsWith(SafetyValidator.CONTROL_FLOW_ERROR)),
                result.getErrors().toString());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.startsWith(SafetyValidator.CONTROL_FLOW_WARNING)));
    }

    @Test
    void legacyControlFlowFlagStillBlocksWhenAnalyzerSeesNoDifference() {
        var cl = cluster(sequence(IF_A), sequence(IF_A));
        flagControlFlow(cl);

        SafetyValidator.ValidationResult result = validator.validate(cl, recommendation);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().contains(SafetyValidator.CONTROL_FLOW_ERROR));
    }

    private static void flagControlFlow(com.raditha.dedup.model.DuplicateCluster cl) {
        VariationAnalysis variations = mock(VariationAnalysis.class);
        when(variations.hasControlFlowDifferences()).thenReturn(true);
        when(variations.getVariations()).thenReturn(List.of());
        SimilarityResult similarity = cl.duplicates().get(0).similarity();
        when(similarity.variations()).thenReturn(variations);
    }

    @Test
    void labelsAreComparedByIdentifierAcrossSeparatelyParsedSequences() {
        String labelled = """
                outer:
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        if (j == %s) { break outer; }
                        if (i == 1) { continue outer; }
                    }
                }
                """;
        ControlFlowVariationAnalyzer analyzer = new ControlFlowVariationAnalyzer();

        ControlFlowVariationAnalyzer.Result same = analyzer.analyze(sequence(labelled.formatted("1")),
                sequence(labelled.formatted("2")));
        assertEquals(ControlFlowVariationAnalyzer.Kind.PARAMETERIZABLE, same.kind(), same.detail());

        ControlFlowVariationAnalyzer.Result different = analyzer.analyze(sequence(labelled.formatted("1")),
                sequence(labelled.formatted("1").replace("break outer", "break")));
        assertEquals(ControlFlowVariationAnalyzer.Kind.UNSAFE, different.kind());
    }
}
