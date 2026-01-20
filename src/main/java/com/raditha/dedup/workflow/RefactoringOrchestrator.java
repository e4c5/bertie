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
        Map<ClassOrInterfaceDeclaration, List<DuplicateCluster>> clustersByClass = groupClustersByClass(report, cu);
        
        // 2. Identify all classes in the CU (even those without duplicates yet, though mostly we care about those with duplicates)
        // Actually, we only need to iterate over classes that HAVE duplicates to refactor.
        // Wait, if we only check identifying duplicates, we might miss the case where a class has no duplicates 
        // initially but we want to run a workflow? No, workflows are triggered by duplicates.
        
        // However, we should robustly handle the case where we can't find the class for a cluster.
        
        for (Map.Entry<ClassOrInterfaceDeclaration, List<DuplicateCluster>> entry : clustersByClass.entrySet()) {
            ClassOrInterfaceDeclaration clazz = entry.getKey();
            List<DuplicateCluster> clusters = entry.getValue();
            
            RefactoringWorkflow workflow = workflowFactory.getWorkflow(clazz);
            RefactoringSession session = workflow.execute(clazz, clusters, cu);
            
            mergeSessions(totalSession, session);
        }

        return totalSession;
    }

    private Map<ClassOrInterfaceDeclaration, List<DuplicateCluster>> groupClustersByClass(
            DuplicationReport report, CompilationUnit cu) {
        
        Map<ClassOrInterfaceDeclaration, List<DuplicateCluster>> map = new HashMap<>();

        for (DuplicateCluster cluster : report.clusters()) {
            // Find the containing class for the primary sequence
            StatementSequence primary = cluster.primary();
            if (primary == null || primary.containingMethod() == null) continue;
            
            Optional<ClassOrInterfaceDeclaration> classOpt = primary.containingMethod()
                    .findAncestor(ClassOrInterfaceDeclaration.class);
            
            if (classOpt.isPresent()) {
                map.computeIfAbsent(classOpt.get(), k -> new ArrayList<>()).add(cluster);
            } else {
                // If checking orphan methods or non-class structures, we might skip or handle differently
                System.out.println("Warning: Could not determine class for cluster " + cluster);
            }
        }
        return map;
    }

    private void mergeSessions(RefactoringSession target, RefactoringSession source) {
        source.getSuccessful().forEach(s -> target.addSuccess(s.cluster(), s.details()));
        source.getSkipped().forEach(s -> target.addSkipped(s.cluster(), s.reason()));
        source.getFailed().forEach(s -> target.addFailed(s.cluster(), s.error()));
    }
}
