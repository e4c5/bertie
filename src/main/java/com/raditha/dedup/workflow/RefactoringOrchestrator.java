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
        if (primary == null || primary.container() == null) {
            orphanedClusters.add(cluster);
            return;
        }

        // Try to find the class from the container (works for all container types)
        Optional<ClassOrInterfaceDeclaration> classOpt = primary.container().findAncestor(ClassOrInterfaceDeclaration.class);

        // ROBUST RESOLUTION: If container is detached or from a different CU, try to find class in current CU
        if (classOpt.isEmpty()) {
            // Strategy 1: Walk up the parent chain from the container node
            com.github.javaparser.ast.Node current = primary.container();
            while (current != null && classOpt.isEmpty()) {
                if (current instanceof ClassOrInterfaceDeclaration clazz) {
                    // Check if this class is in the current CU
                    if (cu.findAll(ClassOrInterfaceDeclaration.class).contains(clazz)) {
                        classOpt = Optional.of(clazz);
                        logger.debug("Resolved class via parent chain: {}", clazz.getNameAsString());
                        break;
                    }
                }
                current = current.getParentNode().orElse(null);
            }
            
            // Strategy 2: For callable containers (methods/constructors), use name-based lookup
            if (classOpt.isEmpty() && primary.getContainingCallable().isPresent()) {
                CallableDeclaration<?> callable = primary.getContainingCallable().get();
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
                    // Pick the most specific (innermost) class
                    classOpt = candidates.stream()
                            .max(java.util.Comparator.comparingInt(c -> c.getRange().map(r -> r.begin.line).orElse(0)));
                    logger.warn("Ambiguous class resolution for callable '{}': {} candidates, picked {}", 
                               name, candidates.size(), classOpt.map(ClassOrInterfaceDeclaration::getNameAsString).orElse("none"));
                }
            }
            
            // Strategy 3: For non-callable containers (lambdas, initializers), use line-based resolution
            if (classOpt.isEmpty()) {
                int containerLine = primary.range().startLine();
                List<ClassOrInterfaceDeclaration> candidates = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                        .filter(c -> c.getRange().isPresent() && 
                                     c.getRange().get().begin.line <= containerLine &&
                                     c.getRange().get().end.line >= containerLine)
                        .toList();
                
                // Pick the most specific (innermost) class - prefer nested classes
                if (!candidates.isEmpty()) {
                    // Sort by start line (later = more nested) and pick the last one
                    List<ClassOrInterfaceDeclaration> mutableCandidates = new ArrayList<>(candidates);
                    mutableCandidates.sort(java.util.Comparator.comparingInt(c -> c.getRange().map(r -> r.begin.line).orElse(0)));
                    classOpt = Optional.of(mutableCandidates.get(mutableCandidates.size() - 1));
                    logger.debug("Resolved class context for non-callable container via line {} -> {}", 
                                 containerLine, classOpt.map(ClassOrInterfaceDeclaration::getNameAsString).orElse("none"));
                }
            }
        }

        if (classOpt.isPresent()) {
            clustersByClass.computeIfAbsent(classOpt.get(), k -> new ArrayList<>()).add(cluster);
        } else {
            // primaryPath is already defined in outer scope
            Path primaryPath = primary.sourceFilePath();
            String containerName = primary.getContainerName();
            logger.warn("DEBUG: Cluster orphaned. Container: {}. Primary Path: {}. CU passed to orchestrate: {}",
                containerName, primaryPath, 
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
                .allMatch(seq -> seq.getContainingCallable().orElse(null) instanceof ConstructorDeclaration);
        
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
