package com.raditha.dedup.workflow;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.refactoring.RefactoringEngine;
import com.raditha.dedup.refactoring.RefactoringEngine.RefactoringSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrator for handling refactoring at the class level.
 * Groups clusters by class and delegates to the appropriate workflow.
 */
public class RefactoringOrchestrator {

    private final WorkflowFactory workflowFactory;
    private final com.raditha.dedup.clustering.RefactoringRecommendationGenerator recommendationGenerator;

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(RefactoringOrchestrator.class);

    /**
     * Creates a new orchestrator.
     *
     * @param analyzer the duplication analyzer for workflows
     * @param engine   the refactoring engine for workflows
     */
    public RefactoringOrchestrator(DuplicationAnalyzer analyzer, RefactoringEngine engine) {
        this.workflowFactory = new WorkflowFactory(analyzer, engine);
        this.recommendationGenerator = new com.raditha.dedup.clustering.RefactoringRecommendationGenerator();
    }

    /**
     * Orchestrate refactoring for a single duplication report (one file).
     */
    public RefactoringSession orchestrate(DuplicationReport report, CompilationUnit cu) 
            throws IOException, InterruptedException {
        
        RefactoringSession totalSession = new RefactoringSession();

        // 1. Group clusters by class
        Map<ClassOrInterfaceDeclaration, List<DuplicateCluster>> clustersByClass = new java.util.LinkedHashMap<>();
        List<DuplicateCluster> orphanedClusters = new ArrayList<>();
        
        groupClusters(report, cu, clustersByClass, orphanedClusters);
        
        // Handle orphaned clusters
        for (DuplicateCluster orphan : orphanedClusters) {
            logger.warn("Skipping cluster with no determinable class context: {}", orphan);
            totalSession.addSkipped(orphan, "Could not determine containing class");
        }

        List<Map.Entry<ClassOrInterfaceDeclaration, List<DuplicateCluster>>> orderedEntries =
                new ArrayList<>(clustersByClass.entrySet());
        orderedEntries.sort(java.util.Comparator
                .comparing((Map.Entry<ClassOrInterfaceDeclaration, List<DuplicateCluster>> e) ->
                        e.getKey().getNameAsString())
                .thenComparingInt(e -> e.getKey().getRange().map(r -> r.begin.line).orElse(0)));

        for (Map.Entry<ClassOrInterfaceDeclaration, List<DuplicateCluster>> entry : orderedEntries) {
            ClassOrInterfaceDeclaration clazz = entry.getKey();
            List<DuplicateCluster> clusters = entry.getValue();
            
            RefactoringWorkflow workflow = workflowFactory.getWorkflow(clazz);
            RefactoringSession session = workflow.execute(clazz, clusters, cu);
            
            mergeSessions(totalSession, session);
        }

        // New Step 3: Cleanup unreferenced private methods (zombies)
        // CRITICAL FIX: Re-parse the CU from disk to ensure we have the fresh state (after rollback if any)
        // The in-memory 'cu' might still contain changes that were rolled back on disk.
        cu = com.github.javaparser.StaticJavaParser.parse(com.raditha.dedup.util.ASTUtility.getSourcePath(cu));

        com.raditha.dedup.refactoring.UnusedMethodCleaner cleaner = new com.raditha.dedup.refactoring.UnusedMethodCleaner();
        boolean cleaned = cleaner.clean(cu);
        
        if (cleaned) {
            logger.info("Cleanup detected unused methods. Saving changes to file.");
            // Determine file path
            java.nio.file.Path path = com.raditha.dedup.util.ASTUtility.getSourcePath(cu);
            java.nio.file.Files.writeString(path, cu.toString());
        }

        return totalSession;
    }

    private void groupClusters(DuplicationReport report, CompilationUnit cu,
            Map<ClassOrInterfaceDeclaration, List<DuplicateCluster>> clustersByClass,
            List<DuplicateCluster> orphanedClusters) {
        
        for (DuplicateCluster cluster : report.clusters()) {
            // Split cross-file constructor clusters into per-file clusters
            List<DuplicateCluster> clustersToProcess = splitConstructorClusters(cluster);
            
            for (DuplicateCluster clusterToGroup : clustersToProcess) {
                groupCluster(cu, clustersByClass, orphanedClusters, clusterToGroup);
            }
        }
        logger.info("DEBUG: Grouping complete. Clusters by class: {}. Orphaned: {}", clustersByClass.size(), orphanedClusters.size());
    }

    private static void groupCluster(CompilationUnit cu, Map<ClassOrInterfaceDeclaration, List<DuplicateCluster>> clustersByClass, List<DuplicateCluster> orphanedClusters, DuplicateCluster cluster) {
        // Find the containing class for the primary sequence
        StatementSequence primary = cluster.primary();
        if (primary == null || primary.containingCallable() == null) {
            orphanedClusters.add(cluster);
            return;
        }

        CallableDeclaration<?> callable = primary.containingCallable();
        Optional<ClassOrInterfaceDeclaration> classOpt = callable.findAncestor(ClassOrInterfaceDeclaration.class);

        // ROBUST RESOLUTION: If method is detached or from a different CU, try to find it in the current CU
        if (classOpt.isEmpty()) {
            String name = callable.getNameAsString();
            List<ClassOrInterfaceDeclaration> candidates = new ArrayList<>();

            if (callable instanceof MethodDeclaration) {
                candidates = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                        .filter(c -> !c.getMethodsByName(name).isEmpty())
                        .toList();
            } else if (callable instanceof ConstructorDeclaration) {
                // For constructors, the name is the class name
                candidates = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                        .filter(c -> c.getNameAsString().equals(name))
                        .toList();
            }

            if (candidates.size() == 1) {
                classOpt = Optional.of(candidates.get(0));
                logger.debug("Robustly resolved class context for orphaned callable: {} -> {}", name, classOpt.get().getNameAsString());
            } else if (candidates.size() > 1) {
                logger.warn("Ambiguous class resolution for callable '{}': {} candidates in CU", name, candidates.size());
            }
        }

        if (classOpt.isPresent()) {
            clustersByClass.computeIfAbsent(classOpt.get(), k -> new ArrayList<>()).add(cluster);
        } else {
            // primaryPath is already defined in outer scope
            Path primaryPath = primary.sourceFilePath();
            String callableName = primary.containingCallable().getNameAsString();
            logger.warn("DEBUG: Cluster orphaned. Callable: {}. Primary Path: {}. CU passed to orchestrate: {}",
                callableName, primaryPath, 
                cu.getStorage().map(com.github.javaparser.ast.CompilationUnit.Storage::getPath).orElse(null));
            orphanedClusters.add(cluster);
        }
    }

    /**
     * Split cross-file constructor clusters into per-file clusters.
     * This allows constructor delegation to work within each file independently.
     * 
     * @param cluster The cluster to potentially split
     * @return List of clusters (original if not split, or multiple per-file clusters)
     */
    List<DuplicateCluster> splitConstructorClusters(DuplicateCluster cluster) {
        // Check if all sequences are constructors
        boolean allConstructors = cluster.allSequences().stream()
                .allMatch(seq -> seq.containingCallable() instanceof ConstructorDeclaration);
        
        if (!allConstructors) {
            // Not a constructor cluster, return as-is
            return List.of(cluster);
        }
        
        // Group sequences by file path
        Map<Path, List<StatementSequence>> byFile = new java.util.HashMap<>();
        for (StatementSequence seq : cluster.allSequences()) {
            Path filePath = seq.sourceFilePath();
            if (filePath != null) {
                byFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(seq);
            }
        }
        
        // If only one file, return as-is
        if (byFile.size() <= 1) {
            return List.of(cluster);
        }
        
        // Create separate clusters for each file
        List<DuplicateCluster> result = new ArrayList<>();
        for (Map.Entry<Path, List<StatementSequence>> entry : byFile.entrySet()) {
            List<StatementSequence> sequences = entry.getValue();
            if (sequences.size() >= 2) { // Need at least 2 constructors to delegate
                // Filter similarity pairs to only include those within this file
                List<com.raditha.dedup.model.SimilarityPair> filePairs = cluster.duplicates().stream()
                        .filter(pair -> sequences.contains(pair.seq1()) && sequences.contains(pair.seq2()))
                        .toList();
                
                if (!filePairs.isEmpty()) {
                    // Use the first sequence as primary
                    StatementSequence primary = sequences.get(0);
                    
                    // Calculate LOC reduction (proportional to cluster size)
                    int locReduction = cluster.estimatedLOCReduction() * sequences.size() / cluster.allSequences().size();
                    
                    // Create the cluster with a NEW recommendation (regenerated for this file)
                    DuplicateCluster tempCluster = new DuplicateCluster(
                            primary, 
                            filePairs, 
                            null,  // Will be replaced
                            locReduction
                    );
                    
                    // Regenerate the recommendation for this file-specific cluster
                    com.raditha.dedup.model.RefactoringRecommendation newRecommendation = 
                            recommendationGenerator.generateRecommendation(tempCluster);
                    
                    DuplicateCluster fileCluster = new DuplicateCluster(
                            primary, 
                            filePairs, 
                            newRecommendation, 
                            locReduction
                    );
                    result.add(fileCluster);
                    logger.info("Split constructor cluster for file: {} ({} constructors)", 
                               entry.getKey().getFileName(), sequences.size());
                }
            }
        }
        
        // If we couldn't create any valid clusters, return the original
        return result.isEmpty() ? List.of(cluster) : result;
    }

    private void mergeSessions(RefactoringSession target, RefactoringSession source) {
        source.getSuccessful().forEach(s -> target.addSuccess(s.cluster(), s.details(), s.diffStats()));
        source.getSkipped().forEach(s -> target.addSkipped(s.cluster(), s.reason()));
        source.getFailed().forEach(s -> target.addFailed(s.cluster(), s.error()));
    }
}
