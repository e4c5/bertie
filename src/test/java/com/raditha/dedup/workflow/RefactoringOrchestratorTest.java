package com.raditha.dedup.workflow;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.SimilarityPair;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefactoringOrchestratorTest {

    private RefactoringOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new RefactoringOrchestrator(null, null);
    }

    @Test
    void testSplitConstructorClusters_AvoidsEmptyClusters() {
        // Setup: A cross-file constructor cluster
        // File A: seqA1, seqA2
        // File B: seqB1
        // Pairs: (seqA1, seqB1), (seqA2, seqB1)
        // Note: No pair (seqA1, seqA2)
        
        Path pathA = Paths.get("FileA.java");
        Path pathB = Paths.get("FileB.java");
        
        StatementSequence seqA1 = mock(StatementSequence.class);
        StatementSequence seqA2 = mock(StatementSequence.class);
        StatementSequence seqB1 = mock(StatementSequence.class);
        
        when(seqA1.sourceFilePath()).thenReturn(pathA);
        when(seqA2.sourceFilePath()).thenReturn(pathA);
        when(seqB1.sourceFilePath()).thenReturn(pathB);
        
        ConstructorDeclaration constA1 = mock(ConstructorDeclaration.class);
        ConstructorDeclaration constA2 = mock(ConstructorDeclaration.class);
        ConstructorDeclaration constB1 = mock(ConstructorDeclaration.class);
        
        when(seqA1.containingCallable()).thenReturn((CallableDeclaration) constA1);
        when(seqA2.containingCallable()).thenReturn((CallableDeclaration) constA2);
        when(seqB1.containingCallable()).thenReturn((CallableDeclaration) constB1);
        
        SimilarityPair pair1 = mock(SimilarityPair.class);
        when(pair1.seq1()).thenReturn(seqA1);
        when(pair1.seq2()).thenReturn(seqB1);
        
        SimilarityPair pair2 = mock(SimilarityPair.class);
        when(pair2.seq1()).thenReturn(seqA2);
        when(pair2.seq2()).thenReturn(seqB1);
        
        DuplicateCluster originalCluster = mock(DuplicateCluster.class);
        when(originalCluster.allSequences()).thenReturn(List.of(seqA1, seqA2, seqB1));
        when(originalCluster.duplicates()).thenReturn(List.of(pair1, pair2));
        when(originalCluster.estimatedLOCReduction()).thenReturn(10);
        
        // Execute
        List<DuplicateCluster> result = orchestrator.splitConstructorClusters(originalCluster);
        
        // Verification:
        // File A has 2 sequences (seqA1, seqA2) but NO similarity pair between them.
        // It should NOT create a cluster for File A.
        // File B has only 1 sequence, so no cluster for File B either.
        // The original logic would have returned an empty result list, which then falls back to original cluster.
        
        assertEquals(1, result.size());
        assertEquals(originalCluster, result.get(0));
    }
}
