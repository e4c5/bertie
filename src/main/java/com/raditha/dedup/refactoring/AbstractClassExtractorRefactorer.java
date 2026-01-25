package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.StatementSequence;

import java.nio.file.Path;
import java.util.*;

/**
 * Abstract base class for refactorers that extract duplicate code into a new
 * class.
 * Provides common functionality for both utility class and parent class
 * extraction.
 */
public abstract class AbstractClassExtractorRefactorer {

    protected DuplicateCluster cluster;
    protected RefactoringRecommendation recommendation;
    protected Map<Path, String> modifiedFiles;
    protected Set<CompilationUnit> involvedCus;
    protected Map<CompilationUnit, Path> cuToPath;
    protected Map<CompilationUnit, MethodDeclaration> methodsToRemove;
    protected String packageName;

    /**
     * Apply the refactoring to extract code into a new class.
     */
    public abstract ExtractMethodRefactorer.RefactoringResult refactor(
            DuplicateCluster cluster, RefactoringRecommendation recommendation);

    /**
     * Initialize common fields for refactoring.
     */
    protected void initialize(DuplicateCluster cluster, RefactoringRecommendation recommendation) {
        this.cluster = cluster;
        this.recommendation = recommendation;
        this.modifiedFiles = new LinkedHashMap<>();

        StatementSequence primary = cluster.primary();
        CompilationUnit primaryCu = primary.compilationUnit();
        this.packageName = getPackageName(primaryCu);

        this.involvedCus = collectInvolvedCUs();
        this.cuToPath = buildCUToPathMap();
        this.methodsToRemove = buildMethodsToRemoveMap();
    }

    /**
     * Collect all CompilationUnits involved in the cluster.
     */
    protected Set<CompilationUnit> collectInvolvedCUs() {
        Set<CompilationUnit> involved = Collections.newSetFromMap(new IdentityHashMap<>());
        involved.add(cluster.primary().compilationUnit());
        cluster.duplicates().forEach(pair -> {
            involved.add(pair.seq1().compilationUnit());
            involved.add(pair.seq2().compilationUnit());
        });
        return involved;
    }

    /**
     * Build a mapping from CompilationUnit to its source file Path.
     */
    protected Map<CompilationUnit, Path> buildCUToPathMap() {
        Map<CompilationUnit, Path> map = new IdentityHashMap<>();
        map.put(cluster.primary().compilationUnit(), cluster.primary().sourceFilePath());
        cluster.duplicates().forEach(pair -> {
            map.put(pair.seq1().compilationUnit(), pair.seq1().sourceFilePath());
            map.put(pair.seq2().compilationUnit(), pair.seq2().sourceFilePath());
        });
        return map;
    }

    /**
     * Build a mapping from CompilationUnit to the method that should be removed.
     */
    protected Map<CompilationUnit, MethodDeclaration> buildMethodsToRemoveMap() {
        Map<CompilationUnit, MethodDeclaration> map = new IdentityHashMap<>();
        StatementSequence primary = cluster.primary();
        map.put(primary.compilationUnit(), primary.containingMethod());
        cluster.duplicates().forEach(pair -> {
            map.put(pair.seq1().compilationUnit(), pair.seq1().containingMethod());
            map.put(pair.seq2().compilationUnit(), pair.seq2().containingMethod());
        });
        return map;
    }

    /**
     * Check if an import is needed by a method.
     * Updated to return true conservatively to ensure all types are available in
     * the new class.
     */
    protected boolean isImportNeeded(ImportDeclaration imp, MethodDeclaration method) {
        return true; // Always copy all imports to be safe
    }

    /**
     * Get package name from a CompilationUnit.
     */
    protected String getPackageName(CompilationUnit cu) {
        return cu.getPackageDeclaration()
                .map(NodeWithName::getNameAsString)
                .orElse("");
    }

    /**
     * Copy necessary imports from source CU to target CU for a given method.
     */
    protected void copyNeededImports(CompilationUnit sourceCu, CompilationUnit targetCu,
            MethodDeclaration method) {
        for (ImportDeclaration imp : sourceCu.getImports()) {
            if (isImportNeeded(imp, method)) {
                targetCu.addImport(imp);
            }
        }
    }

    /**
     * Find the primary class declaration in a CompilationUnit.
     */
    protected Optional<ClassOrInterfaceDeclaration> findPrimaryClass(CompilationUnit cu) {
        return cu.findFirst(ClassOrInterfaceDeclaration.class);
    }
}
