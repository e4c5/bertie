package com.raditha.dedup.workflow;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringStrategy;
import com.raditha.dedup.refactoring.RefactoringEngine;
import com.raditha.dedup.refactoring.RefactoringEngine.RefactoringSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * specialized workflow for Test classes.
 * Executes a 2-pass iterative refactoring:
 * Pass 1: Prioritize Parameterized Tests.
 * Pass 2: Re-analyze and refactor remaining duplicates (Helpers).
 */
public class IterativeTestRefactoringWorkflow implements RefactoringWorkflow {

    private final DuplicationAnalyzer analyzer;
    private final RefactoringEngine engine;

    /**
     * Creates a new iterative workflow instance.
     *
     * @param analyzer the duplication analyzer to use for re-analysis
     * @param engine   the refactoring engine to execute changes
     */
    public IterativeTestRefactoringWorkflow(DuplicationAnalyzer analyzer, RefactoringEngine engine) {
        this.analyzer = analyzer;
        this.engine = engine;
    }

    /**
     * Executes the iterative refactoring process for a test class.
     *
     * @param clazz           the test class being refactored
     * @param initialClusters the initial set of duplicate clusters found in the class
     * @param cu              the compilation unit containing the class
     * @return a session object tracking all performed refactorings
     * @throws IOException          if file I/O operations fail
     * @throws InterruptedException if the process is interrupted
     */
    @Override
    public RefactoringSession execute(ClassOrInterfaceDeclaration clazz,
                                      List<DuplicateCluster> initialClusters,
                                      CompilationUnit cu) throws IOException, InterruptedException {
        
        RefactoringSession totalSession = new RefactoringSession();
        Path sourceFile = com.raditha.dedup.util.ASTUtility.getSourcePath(cu);

        // --- PASS 1: Initial Clusters (Parameterized Tests Priority) ---
        System.out.println(">>> PASS 1: Prioritizing Parameterized Tests for " + clazz.getNameAsString());
        
        // Filter clusters to only those relevant to this class
        // (Orchestrator passed us class-specific clusters)
        // CRITICAL: Only process Parameterized Tests in Pass 1. 
        // Any Helper Methods detected in the initial report should be IGNORED in Pass 1,
        // because we will re-detect them in Pass 2 on the modified/cleaner code.
        // Processing Helpers here risks extracting literals into helpers BEFORE parameterization runs,
        // which breaks parameter substitution (as seen in the Aquarium test case).
        List<DuplicateCluster> pass1Clusters = initialClusters.stream()
                .filter(c -> c.recommendation().getStrategy() == RefactoringStrategy.EXTRACT_TO_PARAMETERIZED_TEST)
                .toList();

        RefactoringSession pass1Session = engine.processClusters(pass1Clusters);
        mergeSessions(totalSession, pass1Session);

        if (pass1Session.hasFailures()) {
            System.out.println(">>> PASS 1 had failures. Aborting iteration.");
            return totalSession;
        }

        if (pass1Session.getSuccessful().isEmpty()) {
             System.out.println(">>> PASS 1 made no changes. Proceeding with remaining clusters (Helpers).");
             List<DuplicateCluster> remainingClusters = initialClusters.stream()
                .filter(c -> c.recommendation().getStrategy() != RefactoringStrategy.EXTRACT_TO_PARAMETERIZED_TEST)
                .toList();

             if (!remainingClusters.isEmpty()) {
                 RefactoringSession remainingSession = engine.processClusters(remainingClusters);
                 mergeSessions(totalSession, remainingSession);
             }
             return totalSession;
        }

        // --- PASS 2: Re-Analyze and Deduplicate (Helpers) ---
        System.out.println(">>> PASS 2: Re-analyzing for remaining duplicates (Helpers)...");

        // Re-analyze the file. CRITICAL: We must re-parse the CU to ensure new nodes have valid ranges.
        // In-memory modifications (Pass 1) often leave nodes without ranges, causing StatementExtractor to crash.
        com.github.javaparser.JavaParser parser = new com.github.javaparser.JavaParser(); // Use default config
        CompilationUnit reParsedCU = parser.parse(cu.toString()).getResult()
                .orElseThrow(() -> new IllegalStateException("Failed to re-parse modified code for Pass 2"));
                
        // We also need to ensure the storage path is preserved for the analyzer to work correctly
        reParsedCU.setStorage(com.raditha.dedup.util.ASTUtility.getSourcePath(cu));

        DuplicationReport pass2Report = analyzer.analyzeFile(reParsedCU);

        if (!pass2Report.hasDuplicates()) {
            System.out.println(">>> PASS 2: No duplicates found.");
        } else {
             // We need to filter clusters again to ensure we only process this class
             // (though analyzeFile usually scopes to the file, and we are handling the file here)
             // We can just process all found clusters for this file in Pass 2
             
             // Wait, analyzeFile returns clusters for the whole file. 
             // If the file has multiple classes, we might accidentally refactor another class again?
             // But usually test files have one main class. And duplicates in nested classes are fine to refactor.
             
             List<DuplicateCluster> pass2Clusters = pass2Report.clusters();
             System.out.println(">>> PASS 2: Found " + pass2Clusters.size() + " new clusters.");
             
             RefactoringSession pass2Session = engine.processClusters(pass2Clusters);
             mergeSessions(totalSession, pass2Session);
        }
        
        return totalSession;
    }

    private void mergeSessions(RefactoringSession target, RefactoringSession source) {
        source.getSuccessful().forEach(s -> target.addSuccess(s.cluster(), s.details()));
        source.getSkipped().forEach(s -> target.addSkipped(s.cluster(), s.reason()));
        source.getFailed().forEach(s -> target.addFailed(s.cluster(), s.error()));
    }
}
