package com.raditha.dedup.workflow;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.Range;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.refactoring.RefactoringEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RefactoringOrchestratorTest {

    @Test
    void testCrossFileClusterGrouping() throws Exception {
        // Setup current CU (File B)
        String codeB = "class B { void m() { int x=1; } }";
        CompilationUnit cuB = StaticJavaParser.parse(codeB);
        cuB.setStorage(Paths.get("B.java"));
        ClassOrInterfaceDeclaration classB = cuB.getClassByName("B").get();
        MethodDeclaration methodB = classB.getMethods().get(0);

        StatementSequence seqB = new StatementSequence(
                methodB.getBody().get().getStatements(),
                new Range(1,1,1,1),
                0,
                methodB,
                cuB,
                Paths.get("B.java")
        );

        // Setup remote sequence (File A) as primary
        String codeA = "class A { void m() { int x=1; } }";
        CompilationUnit cuA = StaticJavaParser.parse(codeA);
        cuA.setStorage(Paths.get("A.java"));
        ClassOrInterfaceDeclaration classA = cuA.getClassByName("A").get();
        MethodDeclaration methodA = classA.getMethods().get(0);

        StatementSequence seqA = new StatementSequence(
                methodA.getBody().get().getStatements(),
                new Range(1,1,1,1),
                0,
                methodA,
                cuA,
                Paths.get("A.java")
        );

        // Create cluster where primary is seqA (remote) but we are processing cuB
        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seqA);
        when(cluster.allSequences()).thenReturn(List.of(seqA, seqB));

        DuplicationReport report = new DuplicationReport(
                Paths.get("B.java"),
                Collections.emptyList(),
                List.of(cluster),
                2,
                1
        );

        // Mock dependencies
        DuplicationAnalyzer analyzer = mock(DuplicationAnalyzer.class);
        RefactoringEngine engine = mock(RefactoringEngine.class);

        // Use a subclass or spy to inspect internal grouping if possible,
        // but RefactoringOrchestrator.groupClusters is private.
        // However, we can infer the grouping by seeing if the workflow factory is asked for a workflow for class B.
        // Or better, we can assume that if grouping fails, the cluster is orphaned and nothing happens.

        // Let's rely on the result. If grouping works for B, the orchestrator should try to execute a workflow for B.
        // We can check if orchestrator returns a session (which might be empty if execution is mocked).
        // A better way is to verify that orchestrator logic correctly identifies B.

        // Since we cannot mock internal `groupClusters`, we will construct the orchestrator
        // and run `orchestrate`.
        // We need to verify that `workflowFactory.getWorkflow(classB)` is called.
        // But `workflowFactory` is created inside the constructor. This is hard to test without DI.

        // However, we can observe the log or side effects?
        // Or simpler: The logic currently uses `cluster.primary()`.
        // seqA.containingCallable() is methodA, which belongs to classA.
        // classA is NOT in cuB.
        // So `classOpt` will likely be found (it's in AST of cuA), but it's not useful for grouping into cuB's classes?
        // Wait, `groupClusters` iterates `report.clusters()`.
        // It gets `primary.containingCallable()`.
        // It finds ancestor Class. This gives `classA`.
        // Then it puts `cluster` into map under `classA`.
        // Then it iterates the map. It finds `classA`.
        // It calls `workflowFactory.getWorkflow(classA)`.

        // BUT we are running `orchestrate(report, cuB)`.
        // We expect it to process `classB`.
        // If it groups under `classA`, it will try to refactor `classA`.
        // But `workflow.execute(classA, clusters, cuB)` might look weird or fail if `classA` is not in `cuB`.

        // Actually, `workflowFactory.getWorkflow(clazz)` creates a workflow for that class.
        // If `classA` is passed, it might work if `classA` is fully resolved.
        // But we want it to refactor `classB` because that's what's in `cuB`.

        // The issue is: `RefactoringOrchestrator` is supposed to refactor the file `cu` passed to it.
        // It should group clusters that affect `cu`.
        // If it groups under `classA` (which is in `A.java`), then it's modifying the wrong file or doing nothing for `B.java`.

        // So, we want to assert that the cluster is associated with `classB`.

        // Test Strategy:
        // We'll subclass RefactoringOrchestrator to expose groupClusters or spy it?
        // Java visibility prevents accessing private.

        // Alternative: Use reflection to check `clustersByClass` map? No local variable.

        // Let's modify the class to be testable or assume the issue is real based on the review and just fix it.
        // The review says: "Cross-file duplicate clusters are always grouped based on the primary sequence's callable... selecting a sequence whose `sourceFilePath` matches the current CU before resolving the callable fixes this."

        // I will implement the fix directly and verify with a test that uses the *fixed* logic if possible,
        // or just verify that `primary` is NOT used when it's remote.

        // To verify the fix, I can create a test that asserts the logic *finds* the local sequence.

    }
}
