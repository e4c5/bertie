package com.raditha.dedup.refactoring;

import com.raditha.dedup.model.*;
import com.raditha.dedup.analyzer.DuplicationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RefactoringEngineUnitTest {

    private SafetyValidator validator;
    private RefactoringVerifier verifier;
    private DiffGenerator diffGenerator;
    private RefactoringEngine engine;

    @BeforeEach
    void setUp() {
        validator = mock(SafetyValidator.class);
        verifier = mock(RefactoringVerifier.class);
        diffGenerator = mock(DiffGenerator.class);
        engine = new RefactoringEngine(RefactoringEngine.RefactoringMode.BATCH, validator, verifier, diffGenerator);
    }

    @Test
    void testConstructors() {
        assertNotNull(new RefactoringEngine(Path.of("."), RefactoringEngine.RefactoringMode.BATCH));
        assertNotNull(new RefactoringEngine(Path.of("."), RefactoringEngine.RefactoringMode.BATCH, com.raditha.dedup.cli.VerifyMode.FAST_COMPILE));
    }

    @Test
    void testGetStrategyPriority() {
        assertEquals(100, engine.getStrategyPriority(mockRecommendation(RefactoringStrategy.EXTRACT_TO_PARAMETERIZED_TEST)));
        assertEquals(90, engine.getStrategyPriority(mockRecommendation(RefactoringStrategy.EXTRACT_PARENT_CLASS)));
        assertEquals(80, engine.getStrategyPriority(mockRecommendation(RefactoringStrategy.EXTRACT_TO_UTILITY_CLASS)));
        assertEquals(50, engine.getStrategyPriority(mockRecommendation(RefactoringStrategy.EXTRACT_HELPER_METHOD)));
        assertEquals(0, engine.getStrategyPriority(mockRecommendation(RefactoringStrategy.MANUAL_REVIEW_REQUIRED)));
        assertEquals(0, engine.getStrategyPriority(null));
    }

    @Test
    void testComparePrimaryLocation() throws Exception {
        DuplicateCluster c1 = mock(DuplicateCluster.class);
        DuplicateCluster c2 = mock(DuplicateCluster.class);
        StatementSequence s1 = mock(StatementSequence.class);
        StatementSequence s2 = mock(StatementSequence.class);

        when(c1.primary()).thenReturn(s1);
        when(c2.primary()).thenReturn(s2);
        
        when(s1.sourceFilePath()).thenReturn(Path.of("A.java"));
        when(s1.range()).thenReturn(new Range(10, 15, 1, 1));
        when(s2.sourceFilePath()).thenReturn(Path.of("A.java"));
        when(s2.range()).thenReturn(new Range(20, 25, 1, 1));

        assertTrue(RefactoringEngine.comparePrimaryLocation(c1, c2) < 0);
        assertTrue(RefactoringEngine.comparePrimaryLocation(c2, c1) > 0);
        
        // Null checks
        assertEquals(0, RefactoringEngine.comparePrimaryLocation(null, null));
        assertEquals(1, RefactoringEngine.comparePrimaryLocation(null, c2));
        assertEquals(-1, RefactoringEngine.comparePrimaryLocation(c1, null));
        
        when(c1.primary()).thenReturn(null);
        assertTrue(RefactoringEngine.comparePrimaryLocation(c1, c2) > 0);
    }

    @Test
    void testCanRefactor_Flows() throws Exception {
        RefactoringEngine.RefactoringSession session = new RefactoringEngine.RefactoringSession();
        DuplicateCluster cluster = mock(DuplicateCluster.class);
        
        // Null recommendation
        assertFalse(engine.canRefactor(session, null, cluster));
        
        // Manual review
        RefactoringRecommendation manualRec = mockRecommendation(RefactoringStrategy.MANUAL_REVIEW_REQUIRED);
        assertFalse(engine.canRefactor(session, manualRec, cluster));
        
        // Batch low confidence
        RefactoringRecommendation lowConfRec = mockRecommendation(RefactoringStrategy.EXTRACT_HELPER_METHOD);
        when(lowConfRec.isHighConfidence()).thenReturn(false);
        when(validator.validate(any(), any())).thenReturn(new SafetyValidator.ValidationResult(Collections.emptyList()));
        assertFalse(engine.canRefactor(session, lowConfRec, cluster));
        
        // Safety warnings
        RefactoringRecommendation warnRec = mockRecommendation(RefactoringStrategy.EXTRACT_HELPER_METHOD);
        when(warnRec.isHighConfidence()).thenReturn(true);
        SafetyValidator.ValidationResult warnResult = mock(SafetyValidator.ValidationResult.class);
        when(warnResult.isValid()).thenReturn(true);
        when(warnResult.hasWarnings()).thenReturn(true);
        when(warnResult.getWarnings()).thenReturn(List.of("Warning"));
        when(validator.validate(any(), any())).thenReturn(warnResult);
        assertTrue(engine.canRefactor(session, warnRec, cluster));
    }

    @Test
    void testApplyRefactoring_Strategies() throws Exception {
        DuplicateCluster cluster = mock(DuplicateCluster.class);
        MethodExtractor.RefactoringResult dummyResult = new MethodExtractor.RefactoringResult(
                Map.of(Path.of("T.java"), "code"), RefactoringStrategy.EXTRACT_HELPER_METHOD, "desc"
        );

        try (MockedConstruction<MethodExtractor> m1 = mockConstruction(MethodExtractor.class, 
                (mock, context) -> when(mock.refactor(any(), any())).thenReturn(dummyResult));
             MockedConstruction<ExtractParameterizedTestRefactorer> m2 = mockConstruction(ExtractParameterizedTestRefactorer.class,
                (mock, context) -> when(mock.refactor(any(), any())).thenReturn(new ExtractParameterizedTestRefactorer.RefactoringResult(Path.of("T.java"), "code")));
             MockedConstruction<UtilityClassExtractor> m3 = mockConstruction(UtilityClassExtractor.class,
                (mock, context) -> when(mock.refactor(any(), any())).thenReturn(dummyResult));
             MockedConstruction<ParentClassExtractor> m4 = mockConstruction(ParentClassExtractor.class,
                (mock, context) -> when(mock.refactor(any(), any())).thenReturn(dummyResult))) {

            assertNotNull(engine.applyRefactoring(cluster, mockRecommendation(RefactoringStrategy.EXTRACT_HELPER_METHOD)));
            assertNotNull(engine.applyRefactoring(cluster, mockRecommendation(RefactoringStrategy.EXTRACT_TO_PARAMETERIZED_TEST)));
            assertNotNull(engine.applyRefactoring(cluster, mockRecommendation(RefactoringStrategy.EXTRACT_TO_UTILITY_CLASS)));
            assertNotNull(engine.applyRefactoring(cluster, mockRecommendation(RefactoringStrategy.EXTRACT_PARENT_CLASS)));
        }
    }

    @Test
    void testRefactorAll_FullFlow() throws Exception {
        DuplicationReport report = mock(DuplicationReport.class);
        DuplicateCluster c1 = mock(DuplicateCluster.class);
        when(report.clusters()).thenReturn(List.of(c1));
        
        RefactoringRecommendation rec = mockRecommendation(RefactoringStrategy.EXTRACT_HELPER_METHOD);
        when(rec.isHighConfidence()).thenReturn(true);
        when(c1.recommendation()).thenReturn(rec);
        when(c1.duplicates()).thenReturn(Collections.emptyList());
        when(c1.estimatedLOCReduction()).thenReturn(10);
        when(c1.primary()).thenReturn(mock(StatementSequence.class));
        
        when(validator.validate(any(), any())).thenReturn(new SafetyValidator.ValidationResult(Collections.emptyList()));
        
        MethodExtractor.RefactoringResult result = mock(MethodExtractor.RefactoringResult.class);
        when(result.modifiedFiles()).thenReturn(Map.of(Path.of("test.java"), "new code"));
        when(result.description()).thenReturn("Success");
        
        RefactoringEngine spyEngine = spy(engine);
        doReturn(result).when(spyEngine).applyRefactoring(any(), any());
        when(verifier.verify()).thenReturn(new RefactoringVerifier.VerificationResult(true, List.of(), "Success"));
        
        assertNotNull(spyEngine.refactorAll(report));
        verify(result).apply();
    }

    @Test
    void testShowDiffAndConfirm_Interaction() throws Exception {
        engine = new RefactoringEngine(RefactoringEngine.RefactoringMode.INTERACTIVE, validator, verifier, diffGenerator);
        RefactoringRecommendation rec = mockRecommendation(RefactoringStrategy.EXTRACT_HELPER_METHOD);
        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.estimatedLOCReduction()).thenReturn(5);
        
        RefactoringEngine spyEngine = Mockito.spy(engine);
        MethodExtractor.RefactoringResult result = mock(MethodExtractor.RefactoringResult.class);
        when(result.modifiedFiles()).thenReturn(Map.of(Path.of("A.java"), "code"));
        doReturn(result).when(spyEngine).applyRefactoring(any(), any());

        // Test with exception during diff generation
        when(diffGenerator.generateUnifiedDiff(any(), any())).thenThrow(new RuntimeException("error"));
        
        InputStream originalIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream("y\n".getBytes()));
            assertTrue(spyEngine.showDiffAndConfirm(cluster, rec));
        } finally {
            System.setIn(originalIn);
        }
    }

    @Test
    void testDryRun_Flow() throws Exception {
        engine = new RefactoringEngine(RefactoringEngine.RefactoringMode.DRY_RUN, validator, verifier, diffGenerator);
        RefactoringRecommendation rec = mockRecommendation(RefactoringStrategy.EXTRACT_HELPER_METHOD);
        MethodExtractor.RefactoringResult result = mock(MethodExtractor.RefactoringResult.class);
        when(result.modifiedFiles()).thenReturn(Map.of(Path.of("A.java"), "code"));
        
        engine.collectDryRunDiff(rec, result, 1);
        engine.printDryRunReport();
        
        // Empty report
        new RefactoringEngine(RefactoringEngine.RefactoringMode.DRY_RUN, validator, verifier, diffGenerator).printDryRunReport();
    }

    @Test
    void testSessionSummary() {
        RefactoringEngine.RefactoringSession session = new RefactoringEngine.RefactoringSession();
        DuplicateCluster c = mock(DuplicateCluster.class);
        session.addSuccess(c, "success");
        session.addSkipped(c, "skipped");
        session.addFailed(c, "failed");
        
        assertEquals(1, session.getSuccessful().size());
        assertEquals(1, session.getSkipped().size());
        assertEquals(1, session.getFailed().size());
        assertTrue(session.hasFailures());
        assertEquals(3, session.getTotalProcessed());
        assertNotNull(session.getSuccessful().get(0).details());
        assertNotNull(session.getSuccessful().get(0).reason());
        assertNotNull(session.getSuccessful().get(0).error());
    }

    private RefactoringRecommendation mockRecommendation(RefactoringStrategy strategy) {
        RefactoringRecommendation rec = mock(RefactoringRecommendation.class);
        when(rec.getStrategy()).thenReturn(strategy);
        when(rec.generateMethodSignature()).thenReturn("void foo()");
        when(rec.formatConfidence()).thenReturn("100%");
        return rec;
    }
}
