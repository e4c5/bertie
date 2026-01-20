package com.raditha.dedup.workflow;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.refactoring.RefactoringEngine;
import com.raditha.dedup.refactoring.RefactoringEngine.RefactoringSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrator for handling refactoring at the class level.
 * Groups clusters by class and delegates to the appropriate workflow.
 */
public class RefactoringOrchestrator {

    private final WorkflowFactory workflowFactory;

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(RefactoringOrchestrator.class);

    public RefactoringOrchestrator(DuplicationAnalyzer analyzer, RefactoringEngine engine) {
        this.workflowFactory = new WorkflowFactory(analyzer, engine);
    }

    /**
     * Orchestrate refactoring for a single duplication report (one file).
     */
    public RefactoringSession orchestrate(DuplicationReport report, CompilationUnit cu) 
            throws IOException, InterruptedException {
        
        RefactoringSession totalSession = new RefactoringSession();

        // 1. Group clusters by class
        Map<ClassOrInterfaceDeclaration, List<DuplicateCluster>> clustersByClass = new HashMap<>();
        List<DuplicateCluster> orphanedClusters = new ArrayList<>();
        
        groupClusters(report, clustersByClass, orphanedClusters);
        
        // Handle orphaned clusters
        for (DuplicateCluster orphan : orphanedClusters) {
            logger.warn("Skipping cluster with no determinable class context: {}", orphan);
            totalSession.addSkipped(orphan, "Could not determine containing class");
        }

        for (Map.Entry<ClassOrInterfaceDeclaration, List<DuplicateCluster>> entry : clustersByClass.entrySet()) {
            ClassOrInterfaceDeclaration clazz = entry.getKey();
            List<DuplicateCluster> clusters = entry.getValue();
            
            RefactoringWorkflow workflow = workflowFactory.getWorkflow(clazz);
            RefactoringSession session = workflow.execute(clazz, clusters, cu);
            
            mergeSessions(totalSession, session);
        }

        // New Step 3: Cleanup unreferenced private methods (zombies)
        com.raditha.dedup.refactoring.UnusedMethodCleaner cleaner = new com.raditha.dedup.refactoring.UnusedMethodCleaner();
        boolean cleaned = cleaner.clean(cu);
        
        if (cleaned) {
            logger.info("Cleanup detected unused methods. Saving changes to file.");
            // Determine file path
            if (cu.getStorage().isPresent()) {
                java.nio.file.Path path = cu.getStorage().get().getPath();
                java.nio.file.Files.writeString(path, cu.toString());
            } else {
                 logger.warn("RefactoringOrchestrator: Cannot save cleanup changes - no file path in CompilationUnit");
            }
        }

        return totalSession;
    }

    private void groupClusters(DuplicationReport report, 
            Map<ClassOrInterfaceDeclaration, List<DuplicateCluster>> clustersByClass,
            List<DuplicateCluster> orphanedClusters) {
        
        for (DuplicateCluster cluster : report.clusters()) {
            // Find the containing class for the primary sequence
            StatementSequence primary = cluster.primary();
            if (primary == null || primary.containingMethod() == null) {
                orphanedClusters.add(cluster);
                continue;
            }
            
            Optional<ClassOrInterfaceDeclaration> classOpt = primary.containingMethod()
                    .findAncestor(ClassOrInterfaceDeclaration.class);
            
            if (classOpt.isPresent()) {
                clustersByClass.computeIfAbsent(classOpt.get(), k -> new ArrayList<>()).add(cluster);
            } else {
                orphanedClusters.add(cluster);
            }
        }
    }

    private void mergeSessions(RefactoringSession target, RefactoringSession source) {
        source.getSuccessful().forEach(s -> target.addSuccess(s.cluster(), s.details()));
        source.getSkipped().forEach(s -> target.addSkipped(s.cluster(), s.reason()));
        source.getFailed().forEach(s -> target.addFailed(s.cluster(), s.error()));
    }
}
