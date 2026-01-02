package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithName;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.StatementSequence;
import sa.com.cloudsolutions.antikythera.depsolver.Graph;
import sa.com.cloudsolutions.antikythera.depsolver.GraphNode;
import sa.com.cloudsolutions.antikythera.depsolver.Resolver;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.Callable;
import sa.com.cloudsolutions.antikythera.parser.MCEWrapper;

import java.nio.file.Path;
import java.util.*;

/**
 * Refactorer that extracts cross-class duplicate methods into a dedicated
 * utility class.
 * Handles multi-file refactoring with utility class creation and call site
 * updates.
 */
public class ExtractUtilityClassRefactorer {

    /**
     * Apply the refactoring to extract a utility class.
     */
    public ExtractMethodRefactorer.RefactoringResult refactor(DuplicateCluster cluster,
            RefactoringRecommendation recommendation) {
        StatementSequence primary = cluster.primary();
        CompilationUnit cu = primary.compilationUnit();
        Path sourceFile = primary.sourceFilePath();

        // Get the method to extract
        MethodDeclaration methodToExtract = primary.containingMethod();

        // Validate the method can be made static
        validateCanBeStatic(methodToExtract);

        // Determine utility class name
        String utilityClassName = determineUtilityClassName(recommendation.suggestedMethodName());
        String packageName = getPackageName(cu);

        // Create the new Utility Class AST
        CompilationUnit utilityCu = createUtilityClass(utilityClassName, packageName, methodToExtract);
        String utilityClassCode = utilityCu.toString();

        // Prepare result map
        Map<Path, String> modifiedFiles = new LinkedHashMap<>();

        // 1. Add key for the new utility class file
        // Assumption: simple package structure mapping to path, relative to source root
        // logic handling elsewhere
        // But here we need a Path key. We can derive it from the source file's parent
        // if in same package,
        // or we might need a "util" subpackage.
        // For simplicity and safety in this context, let's put it in a 'util'
        // subpackage relative to the current file's directory.
        // Note: Real path resolution might be more complex ensuring 'util' directory
        // exists, but RefactoringResult just expects a path key.
        Path utilityPath = sourceFile.getParent().resolve("util").resolve(utilityClassName + ".java");
        modifiedFiles.put(utilityPath, utilityClassCode);

        // 2. Process all Classes in the cluster (Primary + Duplicates)
        // We need to remove the original method from Primary, and replace calls in ALL
        // involved files.
        // Note: The DuplicateCluster contains 'duplicates' which are SimilarityPairs.
        // We need to visit every file involved.

        // Set of all CUs involved
        Set<CompilationUnit> involvedCus = Collections.newSetFromMap(new IdentityHashMap<>());
        involvedCus.add(cu);
        cluster.duplicates().forEach(pair -> {
            involvedCus.add(pair.seq1().compilationUnit());
            involvedCus.add(pair.seq2().compilationUnit());
        });

        // Modify each CU
        Map<CompilationUnit, Path> cuToPath = new IdentityHashMap<>();
        cuToPath.put(cu, sourceFile);
        cluster.duplicates().forEach(pair -> {
            cuToPath.put(pair.seq1().compilationUnit(), pair.seq1().sourceFilePath());
            cuToPath.put(pair.seq2().compilationUnit(), pair.seq2().sourceFilePath());
        });

        // Map CUs to the method that should be removed (Primary + Duplicates)
        Map<CompilationUnit, MethodDeclaration> methodsToRemove = new IdentityHashMap<>();
        methodsToRemove.put(cu, methodToExtract);
        cluster.duplicates()
                .forEach(pair -> methodsToRemove.put(pair.seq2().compilationUnit(), pair.seq2().containingMethod()));

        for (CompilationUnit currentCu : involvedCus) {
            updateCallSitesAndImports(currentCu, methodToExtract, utilityClassName, packageName);

            // Remove the duplicate method definition if strictly mapped
            MethodDeclaration methodToRemove = methodsToRemove.get(currentCu);
            if (methodToRemove != null) {
                // Ensure we are removing the node from the tree
                methodToRemove.remove();
            }

            modifiedFiles.put(cuToPath.get(currentCu), currentCu.toString());
        }

        return new ExtractMethodRefactorer.RefactoringResult(
                modifiedFiles,
                recommendation.strategy(),
                "Extracted to utility class: " + utilityClassName);
    }

    private void validateCanBeStatic(MethodDeclaration method) {
        if (method.isStatic())
            return;

        // Check for 'this'
        if (!method.findAll(ThisExpr.class).isEmpty()) {
            throw new IllegalArgumentException("Method uses 'this' and cannot be made static.");
        }

        // Check for instance field access using a simplified heuristic.
        // A robust check requires full symbol resolution which is not available here.
        // We assume if 'this' is absent, it's likely a static candidate.
    }

    private CompilationUnit createUtilityClass(String className, String packageName, MethodDeclaration originalMethod) {
        CompilationUnit utilCu = new CompilationUnit();
        utilCu.setPackageDeclaration(packageName + ".util");

        // Clone method and make static public
        MethodDeclaration newMethod = originalMethod.clone();
        newMethod.setModifiers(Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC);

        // Create Class
        ClassOrInterfaceDeclaration utilClass = utilCu.addClass(className)
                .setJavadocComment("Utility class for " + getUtilityDescription(className) + ".");

        // Private Constructor
        utilClass.addConstructor(Modifier.Keyword.PRIVATE)
                .setBody(new com.github.javaparser.ast.stmt.BlockStmt()
                        .addStatement(new com.github.javaparser.ast.stmt.ThrowStmt(
                                new com.github.javaparser.ast.expr.ObjectCreationExpr(
                                        null,
                                        new ClassOrInterfaceType(null, "UnsupportedOperationException"),
                                        new NodeList<>(new com.github.javaparser.ast.expr.StringLiteralExpr(
                                                "Utility class"))))));

        utilClass.addMember(newMethod);

        // Copy Imports
        // We need to copy imports from the original CU that are needed by the method.
        // This is tricky without type resolution.
        // Heuristic: Copy ALL imports from original CU.
        // Optimization: Filter imports that seem referenced by types in the method.
        CompilationUnit originalCu = (CompilationUnit) originalMethod.findRootNode();
        for (ImportDeclaration imp : originalCu.getImports()) {
            if (isImportNeeded(imp, newMethod)) {
                utilCu.addImport(imp);
            }
        }

        return utilCu;
    }

    // Heuristic checking if import is relevant to the method
    private boolean isImportNeeded(ImportDeclaration imp, MethodDeclaration method) {
        String importName = imp.getNameAsString();
        String simpleName = importName.substring(importName.lastIndexOf('.') + 1);

        // Check if simpleName appears in the method string
        // This is a rough heuristic but effective enough for handling dependencies.
        // Also check if it's a static import (*)
        if (imp.isAsterisk())
            return false; // Avoid pollution, risky to ignore but safer for now.

        return method.toString().contains(simpleName);
    }

    private void updateCallSitesAndImports(CompilationUnit cu, MethodDeclaration originalMethod,
            String utilityClassName, String originalPackage) {
        String methodName = originalMethod.getNameAsString();

        // Add import
        String utilityRunTimePackage = originalPackage + ".util"; // Assumed location
        cu.addImport(utilityRunTimePackage + "." + utilityClassName);

        // Find the primary type declaration to use as context for resolution
        ClassOrInterfaceDeclaration typeDecl = cu.findFirst(ClassOrInterfaceDeclaration.class).orElse(null);
        if (typeDecl == null) {
            return;
        }
        GraphNode contextNode = Graph.createGraphNode(typeDecl);

        // Update calls: method(...) -> UtilityClass.method(...)
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (call.getNameAsString().equals(methodName) && call.getScope().isEmpty()) {
                MCEWrapper wrapper = Resolver.resolveArgumentTypes(contextNode, call);
                Callable callable = AbstractCompiler.findCallableDeclaration(wrapper, typeDecl).orElse(null);

                if (callable != null && callable.isMethodDeclaration()) {
                    MethodDeclaration resolvedMethod = callable.asMethodDeclaration();
                    // Check if the resolved method is indeed the one we are extracting (or a
                    // signature match)
                    // We compare signatures since 'originalMethod' might be from a different file
                    // (duplicate)
                    if (resolvedMethod.getSignature().equals(originalMethod.getSignature())) {
                        call.setScope(new NameExpr(utilityClassName));
                    }
                }
            }
        });
    }

    private String getUtilityDescription(String className) {
        return switch (className) {
            case "ValidationUtils" -> "validation operations";
            case "StringUtils" -> "string manipulation";
            case "DateUtils" -> "date and time operations";
            case "MathUtils" -> "mathematical calculations";
            default -> "common operations";
        };
    }

    private String determineUtilityClassName(String methodName) {
        if (methodName.startsWith("is") || methodName.startsWith("validate")) {
            return "ValidationUtils";
        } else if (methodName.contains("String") || methodName.contains("Format")) {
            return "StringUtils";
        } else if (methodName.contains("Date") || methodName.contains("Time")) {
            return "DateUtils";
        } else if (methodName.contains("Math") || methodName.contains("Calculate")) {
            return "MathUtils";
        }
        return "CommonUtils";
    }

    private String getPackageName(CompilationUnit cu) {
        return cu.getPackageDeclaration()
                .map(NodeWithName::getNameAsString)
                .orElse("");
    }
}
