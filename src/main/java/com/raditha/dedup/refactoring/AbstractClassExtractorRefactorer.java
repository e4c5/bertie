package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.StatementSequence;

import java.nio.file.Path;
import java.util.*;

/**
 * Abstract base class for refactorers that extract duplicate code into a new class.
 * Provides common functionality for both utility class and parent class extraction.
 */
public abstract class AbstractClassExtractorRefactorer {

    /**
     * Apply the refactoring to extract code into a new class.
     */
    public abstract ExtractMethodRefactorer.RefactoringResult refactor(
            DuplicateCluster cluster, RefactoringRecommendation recommendation);

    /**
     * Collect all CompilationUnits involved in the cluster.
     */
    protected Set<CompilationUnit> collectInvolvedCUs(DuplicateCluster cluster) {
        Set<CompilationUnit> involvedCus = Collections.newSetFromMap(new IdentityHashMap<>());
        involvedCus.add(cluster.primary().compilationUnit());
        cluster.duplicates().forEach(pair -> {
            involvedCus.add(pair.seq1().compilationUnit());
            involvedCus.add(pair.seq2().compilationUnit());
        });
        return involvedCus;
    }

    /**
     * Build a mapping from CompilationUnit to its source file Path.
     */
    protected Map<CompilationUnit, Path> buildCUToPathMap(DuplicateCluster cluster) {
        Map<CompilationUnit, Path> cuToPath = new IdentityHashMap<>();
        cuToPath.put(cluster.primary().compilationUnit(), cluster.primary().sourceFilePath());
        cluster.duplicates().forEach(pair -> {
            cuToPath.put(pair.seq1().compilationUnit(), pair.seq1().sourceFilePath());
            cuToPath.put(pair.seq2().compilationUnit(), pair.seq2().sourceFilePath());
        });
        return cuToPath;
    }

    /**
     * Build a mapping from CompilationUnit to the method that should be removed.
     */
    protected Map<CompilationUnit, MethodDeclaration> buildMethodsToRemoveMap(DuplicateCluster cluster) {
        Map<CompilationUnit, MethodDeclaration> methodsToRemove = new IdentityHashMap<>();
        StatementSequence primary = cluster.primary();
        methodsToRemove.put(primary.compilationUnit(), primary.containingMethod());
        cluster.duplicates().forEach(pair -> {
            methodsToRemove.put(pair.seq1().compilationUnit(), pair.seq1().containingMethod());
            methodsToRemove.put(pair.seq2().compilationUnit(), pair.seq2().containingMethod());
        });
        return methodsToRemove;
    }

    /**
     * Check if an import is needed by a method (heuristic based on type references).
     */
    protected boolean isImportNeeded(ImportDeclaration imp, MethodDeclaration method) {
        if (imp.isAsterisk()) {
            return false;
        }

        String importName = imp.getNameAsString();
        String simpleName = importName.substring(importName.lastIndexOf('.') + 1);

        // Check explicit types
        for (ClassOrInterfaceType type : method.findAll(ClassOrInterfaceType.class)) {
            if (type.getNameAsString().equals(simpleName)) {
                return true;
            }
        }

        // Check annotations
        for (AnnotationExpr annotation : method.findAll(AnnotationExpr.class)) {
            if (annotation.getNameAsString().equals(simpleName)) {
                return true;
            }
        }

        // Check Name expressions
        for (NameExpr name : method.findAll(NameExpr.class)) {
            if (name.getNameAsString().equals(simpleName)) {
                return true;
            }
        }

        return false;
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
