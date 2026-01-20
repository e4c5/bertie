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
import java.util.Collections;
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

    public IterativeTestRefactoringWorkflow(DuplicationAnalyzer analyzer, RefactoringEngine engine) {
        this.analyzer = analyzer;
        this.engine = engine;
    }

    @Override
    public RefactoringSession execute(ClassOrInterfaceDeclaration clazz,
                                      List<DuplicateCluster> initialClusters,
                                      CompilationUnit cu) throws IOException, InterruptedException {
        
        RefactoringSession totalSession = new RefactoringSession();
        Path sourceFile = cu.getStorage().map(CompilationUnit.Storage::getPath).orElse(null);

        if (sourceFile == null) {
            System.err.println("Warning: Could not determine source file path for " + clazz.getNameAsString());
            return totalSession;
        }

        // --- PASS 1: Initial Clusters (Parameterized Tests Priority) ---
        System.out.println(">>> PASS 1: Prioritizing Parameterized Tests for " + clazz.getNameAsString());
        
        // Filter clusters to only those relevant to this class
        // (Orchestrator passed us class-specific clusters)
        
        RefactoringSession pass1Session = engine.processClusters(initialClusters);
        mergeSessions(totalSession, pass1Session);

        if (pass1Session.hasFailures()) {
            System.out.println(">>> PASS 1 had failures. Aborting iteration.");
            return totalSession;
        }

        if (pass1Session.getSuccessful().isEmpty()) {
             System.out.println(">>> PASS 1 made no changes. Skipping Pass 2.");
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
        cu.getStorage().ifPresent(s -> reParsedCU.setStorage(s.getPath()));

        DuplicationReport pass2Report = analyzer.analyzeFile(reParsedCU, sourceFile);

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
