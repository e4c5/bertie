package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SafetyValidatorTest {

    private SafetyValidator validator;
    private DuplicateCluster cluster;
    private RefactoringRecommendation recommendation;

    @BeforeEach
    void setUp() {
        validator = new SafetyValidator();
        cluster = mock(DuplicateCluster.class);
        recommendation = mock(RefactoringRecommendation.class);
        
        when(recommendation.getSuggestedMethodName()).thenReturn("newMethod");
        when(recommendation.getSuggestedParameters()).thenReturn(List.of());
        when(cluster.duplicates()).thenReturn(List.of());
    }

    @Test
    void testValidationResultUtilities() {
        SafetyValidator.ValidationIssue error = SafetyValidator.ValidationIssue.error("error message");
        SafetyValidator.ValidationIssue warning = SafetyValidator.ValidationIssue.warning("warning message");
        
        SafetyValidator.ValidationResult result = new SafetyValidator.ValidationResult(List.of(error, warning));
        
        assertFalse(result.isValid());
        assertTrue(result.hasWarnings());
        assertEquals(1, result.getErrors().size());
        assertEquals("error message", result.getErrors().get(0));
        assertEquals(1, result.getWarnings().size());
        assertEquals("warning message", result.getWarnings().get(0));
    }

    @Test
    void testValidateSuccess() {
        StatementSequence seq = mock(StatementSequence.class);
        when(cluster.primary()).thenReturn(seq);
        when(seq.containingMethod()).thenReturn(null); // No class context, no conflict possible
        
        SafetyValidator.ValidationResult result = validator.validate(cluster, recommendation);
        
        assertTrue(result.isValid());
        assertFalse(result.hasWarnings());
    }

    @Test
    void testMethodNameConflict() {
        StatementSequence seq = mock(StatementSequence.class);
        CompilationUnit cu = StaticJavaParser.parse("class A { void existingMethod() {} }");
        ClassOrInterfaceDeclaration clazz = cu.getClassByName("A").get();
        
        when(cluster.primary()).thenReturn(seq);
        when(seq.containingMethod()).thenReturn(clazz.getMethods().get(0));
        when(recommendation.getSuggestedMethodName()).thenReturn("existingMethod");
        
        SafetyValidator.ValidationResult result = validator.validate(cluster, recommendation);
        
        assertFalse(result.isValid());
        assertTrue(result.getErrors().contains("Method name 'existingMethod' already exists in class"));
    }

    @Test
    void testHighParameterCountWarning() {
        StatementSequence seq = mock(StatementSequence.class);
        when(cluster.primary()).thenReturn(seq);
        
        List<ParameterSpec> params = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            params.add(mock(ParameterSpec.class));
        }
        when(recommendation.getSuggestedParameters()).thenReturn(params);
        
        SafetyValidator.ValidationResult result = validator.validate(cluster, recommendation);
        
        assertTrue(result.isValid()); // Warnings don't make it invalid
        assertTrue(result.hasWarnings());
        assertTrue(result.getWarnings().get(0).contains("More than 5 parameters"));
    }

    @Test
    void testControlFlowDifferences() {
        StatementSequence seq = mock(StatementSequence.class);
        when(seq.statements()).thenReturn(List.of());
        when(cluster.primary()).thenReturn(seq);
        
        SimilarityPair pair = mock(SimilarityPair.class);
        when(pair.seq1()).thenReturn(seq);
        when(pair.seq2()).thenReturn(seq);
        
        SimilarityResult similarity = mock(SimilarityResult.class);
        VariationAnalysis variations = mock(VariationAnalysis.class);
        
        when(cluster.duplicates()).thenReturn(List.of(pair));
        when(pair.similarity()).thenReturn(similarity);
        when(similarity.variations()).thenReturn(variations);
        when(variations.hasControlFlowDifferences()).thenReturn(true);
        
        SafetyValidator.ValidationResult result = validator.validate(cluster, recommendation);
        
        assertFalse(result.isValid());
        assertTrue(result.getErrors().contains("Control flow differs between duplicates - cannot safely refactor"));
    }

    @Test
    void testVariableScopeIssuesInDuplicate() {
        StatementSequence primary = mock(StatementSequence.class);
        when(primary.statements()).thenReturn(List.of());
        when(cluster.primary()).thenReturn(primary);
        
        // s2 modified y which is outer scope
        Statement s2 = StaticJavaParser.parseStatement("y = 10;");
        StatementSequence seq2 = mock(StatementSequence.class);
        when(seq2.statements()).thenReturn(List.of(s2));
        
        SimilarityPair pair = mock(SimilarityPair.class);
        when(pair.seq1()).thenReturn(primary);
        when(pair.seq2()).thenReturn(seq2);
        when(cluster.duplicates()).thenReturn(List.of(pair));
        
        SafetyValidator.ValidationResult result = validator.validate(cluster, recommendation);
        
        assertFalse(result.isValid());
        assertTrue(result.getErrors().contains("Variables used are not in scope for extraction"));
    }

    @Test
    void testValidateCleanWithDuplicates() {
        StatementSequence primary = mock(StatementSequence.class);
        when(primary.statements()).thenReturn(List.of());
        when(cluster.primary()).thenReturn(primary);
        
        StatementSequence seq1 = mock(StatementSequence.class);
        when(seq1.statements()).thenReturn(List.of());
        StatementSequence seq2 = mock(StatementSequence.class);
        when(seq2.statements()).thenReturn(List.of());
        
        SimilarityPair pair = mock(SimilarityPair.class);
        when(pair.seq1()).thenReturn(seq1);
        when(pair.seq2()).thenReturn(seq2);
        
        SimilarityResult similarity = mock(SimilarityResult.class);
        VariationAnalysis variations = mock(VariationAnalysis.class);
        when(pair.similarity()).thenReturn(similarity);
        when(similarity.variations()).thenReturn(variations);
        when(variations.hasControlFlowDifferences()).thenReturn(false);
        
        when(cluster.duplicates()).thenReturn(List.of(pair));
        
        SafetyValidator.ValidationResult result = validator.validate(cluster, recommendation);
        
        assertTrue(result.isValid());
        assertFalse(result.hasWarnings());
    }

    @Test
    void testIncompatibleAnnotations() {
        // hasIncompatibleAnnotations currently returns false, so this should pass
        StatementSequence seq = mock(StatementSequence.class);
        when(seq.statements()).thenReturn(List.of());
        when(cluster.primary()).thenReturn(seq);
        
        SafetyValidator.ValidationResult result = validator.validate(cluster, recommendation);
        assertFalse(result.hasWarnings()); 
    }
}
