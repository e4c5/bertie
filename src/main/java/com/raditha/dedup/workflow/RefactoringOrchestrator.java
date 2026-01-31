package com.raditha.dedup.workflow;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
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
        
        groupClusters(report, cu, clustersByClass, orphanedClusters);
        
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
        // CRITICAL FIX: Re-parse the CU from disk to ensure we have the fresh state (after rollback if any)
        // The in-memory 'cu' might still contain changes that were rolled back on disk.
        if (cu.getStorage().isPresent()) {
             cu = com.github.javaparser.StaticJavaParser.parse(cu.getStorage().get().getPath());
        }

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

    private void groupClusters(DuplicationReport report, CompilationUnit cu,
            Map<ClassOrInterfaceDeclaration, List<DuplicateCluster>> clustersByClass,
            List<DuplicateCluster> orphanedClusters) {
        
        for (DuplicateCluster cluster : report.clusters()) {
            // Find the containing class for the primary sequence
            StatementSequence primary = cluster.primary();
            if (primary == null || primary.containingMethod() == null) {
                orphanedClusters.add(cluster);
                continue;
            }
            
            MethodDeclaration method = primary.containingMethod();
            Optional<ClassOrInterfaceDeclaration> classOpt = method.findAncestor(ClassOrInterfaceDeclaration.class);
            
            // ROBUST RESOLUTION: If method is detached or from a different CU, try to find it in the current CU
            if (classOpt.isEmpty()) {
                String methodName = method.getNameAsString();
                List<ClassOrInterfaceDeclaration> candidates = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                        .filter(c -> c.getMethodsByName(methodName).size() > 0)
                        .toList();
                
                if (candidates.size() == 1) {
                    classOpt = Optional.of(candidates.get(0));
                    logger.debug("Robustly resolved class context for orphaned method: {} -> {}", methodName, classOpt.get().getNameAsString());
                } else if (candidates.size() > 1) {
                    logger.warn("Ambiguous class resolution for method '{}': {} candidates in CU", methodName, candidates.size());
                }
            }
            
            if (classOpt.isPresent()) {
                clustersByClass.computeIfAbsent(classOpt.get(), k -> new ArrayList<>()).add(cluster);
            } else {
                logger.warn("DEBUG: Cluster orphaned because no ClassOrInterfaceDeclaration ancestor found for method: {}", 
                    primary.containingMethod().getNameAsString());
                orphanedClusters.add(cluster);
            }
        }
        logger.info("DEBUG: Grouping complete. Clusters by class: {}. Orphaned: {}", clustersByClass.size(), orphanedClusters.size());
    }

    private void mergeSessions(RefactoringSession target, RefactoringSession source) {
        source.getSuccessful().forEach(s -> target.addSuccess(s.cluster(), s.details()));
        source.getSkipped().forEach(s -> target.addSkipped(s.cluster(), s.reason()));
        source.getFailed().forEach(s -> target.addFailed(s.cluster(), s.error()));
    }
}
