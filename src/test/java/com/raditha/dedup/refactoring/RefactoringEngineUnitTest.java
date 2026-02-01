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
        assertEquals(100, RefactoringEngine.getStrategyPriority(mockRec(RefactoringStrategy.EXTRACT_TO_PARAMETERIZED_TEST)));
        assertEquals(90, RefactoringEngine.getStrategyPriority(mockRec(RefactoringStrategy.EXTRACT_PARENT_CLASS)));
        assertEquals(80, RefactoringEngine.getStrategyPriority(mockRec(RefactoringStrategy.EXTRACT_TO_UTILITY_CLASS)));
        assertEquals(50, RefactoringEngine.getStrategyPriority(mockRec(RefactoringStrategy.EXTRACT_HELPER_METHOD)));
        assertEquals(0, RefactoringEngine.getStrategyPriority(mockRec(RefactoringStrategy.MANUAL_REVIEW_REQUIRED)));
        assertEquals(0, RefactoringEngine.getStrategyPriority(null));
    }

    @Test
    void testComparePrimaryLocation_AllBranches() {
        StatementSequence s1 = mock(StatementSequence.class);
        DuplicateCluster c1 = new DuplicateCluster(s1, Collections.emptyList(), null, 0);
        DuplicateCluster c2 = new DuplicateCluster(null, Collections.emptyList(), null, 0);

        assertEquals(0, RefactoringEngine.comparePrimaryLocation(null, null));
        assertEquals(1, RefactoringEngine.comparePrimaryLocation(null, c1));
        assertEquals(-1, RefactoringEngine.comparePrimaryLocation(c1, null));
        
        assertEquals(1, RefactoringEngine.comparePrimaryLocation(c2, c1));
        assertEquals(-1, RefactoringEngine.comparePrimaryLocation(c1, c2));
        assertEquals(0, RefactoringEngine.comparePrimaryLocation(c2, c2));
    }

    @Test
    void testCanRefactor_Flows() throws Exception {
        RefactoringEngine.RefactoringSession session = new RefactoringEngine.RefactoringSession();
        DuplicateCluster cluster = new DuplicateCluster(null, Collections.emptyList(), null, 0);
        
        // Null recommendation
        assertFalse(engine.canRefactor(session, null, cluster));
        
        // Manual review
        RefactoringRecommendation manualRec = mockRec(RefactoringStrategy.MANUAL_REVIEW_REQUIRED);
        assertFalse(engine.canRefactor(session, manualRec, cluster));
        
        // Batch low confidence
        RefactoringRecommendation lowConfRec = mockRec(RefactoringStrategy.EXTRACT_HELPER_METHOD);
        when(lowConfRec.isHighConfidence()).thenReturn(false);
        when(validator.validate(any(), any())).thenReturn(new SafetyValidator.ValidationResult(Collections.emptyList()));
        assertFalse(engine.canRefactor(session, lowConfRec, cluster));
        
        // Safety warnings
        RefactoringRecommendation warnRec = mockRec(RefactoringStrategy.EXTRACT_HELPER_METHOD);
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
        DuplicateCluster cluster = new DuplicateCluster(null, Collections.emptyList(), null, 0);
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

            assertNotNull(engine.applyRefactoring(cluster, mockRec(RefactoringStrategy.EXTRACT_HELPER_METHOD)));
            assertNotNull(engine.applyRefactoring(cluster, mockRec(RefactoringStrategy.EXTRACT_TO_PARAMETERIZED_TEST)));
            assertNotNull(engine.applyRefactoring(cluster, mockRec(RefactoringStrategy.EXTRACT_TO_UTILITY_CLASS)));
            assertNotNull(engine.applyRefactoring(cluster, mockRec(RefactoringStrategy.EXTRACT_PARENT_CLASS)));
        }
    }

    @Test
    void testRefactorAll_Ties() throws Exception {
        DuplicationReport report = mock(DuplicationReport.class);
        RefactoringRecommendation rec = mockRec(RefactoringStrategy.EXTRACT_HELPER_METHOD);
        
        // Tie in strategy and LOC, but different sequence size
        StatementSequence s1 = mock(StatementSequence.class);
        when(s1.statements()).thenReturn(List.of(mock(com.github.javaparser.ast.stmt.Statement.class)));
        DuplicateCluster c1 = new DuplicateCluster(s1, Collections.emptyList(), rec, 10);
        DuplicateCluster c2 = new DuplicateCluster(null, Collections.emptyList(), rec, 10);
        
        when(report.clusters()).thenReturn(List.of(c1, c2));
        RefactoringEngine spyEngine = spy(engine);
        doReturn(new RefactoringEngine.RefactoringSession()).when(spyEngine).processClusters(any());
        spyEngine.refactorAll(report);

        // Tie in size, but different similarity
        SimilarityResult simRes1 = mock(SimilarityResult.class);
        SimilarityResult simRes2 = mock(SimilarityResult.class);
        when(simRes1.overallScore()).thenReturn(0.9);
        when(simRes2.overallScore()).thenReturn(0.8);
        
        SimilarityPair p1 = new SimilarityPair(null, null, simRes1);
        SimilarityPair p2 = new SimilarityPair(null, null, simRes2);
        
        DuplicateCluster c3 = new DuplicateCluster(null, List.of(p1), rec, 10);
        DuplicateCluster c4 = new DuplicateCluster(null, List.of(p2), rec, 10);
        when(report.clusters()).thenReturn(List.of(c3, c4));
        spyEngine.refactorAll(report);
    }

    @Test
    void testRefactorAll_FullFlow() throws Exception {
        DuplicationReport report = mock(DuplicationReport.class);
        StatementSequence primary = mock(StatementSequence.class);
        when(primary.sourceFilePath()).thenReturn(Path.of("T.java"));
        
        RefactoringRecommendation rec = mockRec(RefactoringStrategy.EXTRACT_HELPER_METHOD);
        when(rec.isHighConfidence()).thenReturn(true);
        
        DuplicateCluster cluster = new DuplicateCluster(primary, Collections.emptyList(), rec, 10);
        when(report.clusters()).thenReturn(List.of(cluster));
        
        when(validator.validate(any(), any())).thenReturn(new SafetyValidator.ValidationResult(Collections.emptyList()));
        
        MethodExtractor.RefactoringResult res = mock(MethodExtractor.RefactoringResult.class);
        when(res.modifiedFiles()).thenReturn(Map.of(Path.of("T.java"), "code"));
        when(res.description()).thenReturn("Ok");
        
        RefactoringEngine spyEngine = spy(engine);
        doReturn(res).when(spyEngine).applyRefactoring(any(), any());
        when(verifier.verify()).thenReturn(new RefactoringVerifier.VerificationResult(true, List.of(), "Ok"));
        
        assertNotNull(spyEngine.refactorAll(report));
        verify(res).apply();
    }

    @Test
    void testInteraction_And_DryRun() throws Exception {
        // Interaction
        engine = new RefactoringEngine(RefactoringEngine.RefactoringMode.INTERACTIVE, validator, verifier, diffGenerator);
        RefactoringRecommendation rec = mockRec(RefactoringStrategy.EXTRACT_HELPER_METHOD);
        DuplicateCluster cluster = new DuplicateCluster(null, Collections.emptyList(), rec, 10);
        
        RefactoringEngine spyEngine = spy(engine);
        MethodExtractor.RefactoringResult res = mock(MethodExtractor.RefactoringResult.class);
        when(res.modifiedFiles()).thenReturn(Map.of(Path.of("A.java"), "code"));
        doReturn(res).when(spyEngine).applyRefactoring(any(), any());

        InputStream in = System.in;
        try {
            System.setIn(new ByteArrayInputStream("y\n".getBytes()));
            assertTrue(spyEngine.showDiffAndConfirm(cluster, rec));
        } finally {
            System.setIn(in);
        }

        // Dry Run
        engine = new RefactoringEngine(RefactoringEngine.RefactoringMode.DRY_RUN, validator, verifier, diffGenerator);
        engine.collectDryRunDiff(rec, res, 1);
        engine.printDryRunReport();
    }

    @Test
    void testRefactoringException() throws Exception {
        DuplicationReport report = mock(DuplicationReport.class);
        StatementSequence primary = mock(StatementSequence.class);
        DuplicateCluster cluster = new DuplicateCluster(primary, Collections.emptyList(), mockRec(RefactoringStrategy.EXTRACT_HELPER_METHOD), 10);
        when(report.clusters()).thenReturn(List.of(cluster));
        
        when(validator.validate(any(), any())).thenReturn(new SafetyValidator.ValidationResult(Collections.emptyList()));
        
        RefactoringEngine spyEngine = spy(engine);
        doThrow(new RuntimeException("Test Error")).when(spyEngine).applyRefactoring(any(), any());
        
        RefactoringEngine.RefactoringSession session = spyEngine.refactorAll(report);
        assertEquals(1, session.getFailed().size());
        verify(verifier).rollback();
    }

    @Test
    void testSessionSummary() {
        RefactoringEngine.RefactoringSession session = new RefactoringEngine.RefactoringSession();
        DuplicateCluster c = new DuplicateCluster(null, Collections.emptyList(), null, 0);
        session.addSuccess(c, "success");
        session.addSkipped(c, "skipped");
        session.addFailed(c, "failed");
        
        assertEquals(1, session.getSuccessful().size());
        assertEquals(3, session.getTotalProcessed());
    }

    private RefactoringRecommendation mockRec(RefactoringStrategy strategy) {
        RefactoringRecommendation rec = mock(RefactoringRecommendation.class);
        when(rec.getStrategy()).thenReturn(strategy);
        when(rec.isHighConfidence()).thenReturn(true);
        when(rec.generateMethodSignature()).thenReturn("void foo()");
        when(rec.formatConfidence()).thenReturn("100%");
        return rec;
    }
}
