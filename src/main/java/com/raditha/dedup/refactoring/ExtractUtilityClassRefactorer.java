package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;

import java.io.IOException;
import java.nio.file.Files;
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
    public RefactoringResult refactor(DuplicateCluster cluster, RefactoringRecommendation recommendation)
            throws IOException {

        // For now, this is a simplified version that works within a single file
        // Full cross-file implementation would require tracking multiple source files
        CompilationUnit cu = cluster.primary().compilationUnit();
        Path sourceFile = cluster.primary().sourceFilePath();

        // Get the class containing the duplicate method
        ClassOrInterfaceDeclaration sourceClass = cu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow(() -> new IllegalStateException("No class found"));

        // Get the method to extract
        MethodDeclaration methodToExtract = cluster.primary().containingMethod();

        // Validate the method can be made static (simplified check)
        if (!canBeStatic(methodToExtract)) {
            throw new IllegalArgumentException(
                    "Method cannot be extracted to utility class - uses instance state");
        }

        // Determine utility class name (simplified - from recommendation)
        String utilityClassName = determineUtilityClassName(recommendation.suggestedMethodName());

        // Generate utility class content
        String utilityClassCode = generateUtilityClass(
                utilityClassName,
                methodToExtract,
                getPackageName(cu));

        // For now, return the utility class as the refactored code
        // In a full implementation, this would:
        // 1. Create the new utility class file
        // 2. Update all call sites across multiple files
        // 3. Remove the original method

        return new RefactoringResult(
                sourceFile,
                utilityClassCode,
                utilityClassName);
    }

    /**
     * Check if a method can be made static.
     */
    private boolean canBeStatic(MethodDeclaration method) {
        // Simplified check - a method can be static if:
        // 1. It doesn't use 'this' keyword
        // 2. It doesn't access instance fields
        // 3. It's not already static

        if (method.isStatic()) {
            return true; // Already static
        }

        // Check for 'this' references
        boolean usesThis = !method.findAll(com.github.javaparser.ast.expr.ThisExpr.class).isEmpty();
        if (usesThis) {
            return false;
        }

        // Check for field access (simplified - just look for simple name expressions)
        // In a full implementation, would need semantic analysis
        return true; // Simplified - assume can be static
    }

    /**
     * Determine utility class name from method name.
     */
    private String determineUtilityClassName(String methodName) {
        // Simple heuristic - derive from method name
        if (methodName.startsWith("is") || methodName.startsWith("validate")) {
            return "ValidationUtils";
        } else if (methodName.contains("String") || methodName.contains("Format")) {
            return "StringUtils";
        } else if (methodName.contains("Date") || methodName.contains("Time")) {
            return "DateUtils";
        } else if (methodName.contains("Math") || methodName.contains("Calculate")) {
            return "MathUtils";
        }
        return "CommonUtils"; // Default
    }

    /**
     * Get package name from compilation unit.
     */
    private String getPackageName(CompilationUnit cu) {
        return cu.getPackageDeclaration()
                .map(pd -> pd.getNameAsString())
                .orElse("com.example");
    }

    /**
     * Generate the utility class code.
     */
    private String generateUtilityClass(
            String className,
            MethodDeclaration method,
            String packageName) {

        StringBuilder sb = new StringBuilder();

        // Package declaration
        sb.append("package ").append(packageName).append(".util;\n\n");

        // Imports (if needed)
        Set<String> imports = extractImports(method);
        for (String importStr : imports) {
            sb.append("import ").append(importStr).append(";\n");
        }
        if (!imports.isEmpty()) {
            sb.append("\n");
        }

        // Class declaration
        sb.append("/**\n");
        sb.append(" * Utility class for ").append(getUtilityDescription(className)).append(".\n");
        sb.append(" */\n");
        sb.append("public class ").append(className).append(" {\n\n");

        // Private constructor
        sb.append("    /**\n");
        sb.append("     * Private constructor to prevent instantiation.\n");
        sb.append("     */\n");
        sb.append("    private ").append(className).append("() {\n");
        sb.append("        throw new UnsupportedOperationException(\"Utility class\");\n");
        sb.append("    }\n\n");

        // Utility method (make it static and public)
        String methodCode = method.toString();

        // Make sure it's static and public
        methodCode = methodCode.replace("private ", "public static ");
        if (!methodCode.contains("static")) {
            methodCode = methodCode.replace("public ", "public static ");
        }

        // Add indentation
        String[] lines = methodCode.split("\n");
        for (String line : lines) {
            sb.append("    ").append(line).append("\n");
        }

        sb.append("}\n");

        return sb.toString();
    }

    /**
     * Extract required imports from method.
     */
    private Set<String> extractImports(MethodDeclaration method) {
        Set<String> imports = new LinkedHashSet<>();

        // Check for common types that need imports
        String methodStr = method.toString();

        if (methodStr.contains("List<") || methodStr.contains("ArrayList<")) {
            imports.add("java.util.List");
            imports.add("java.util.ArrayList");
        }
        if (methodStr.contains("Map<") || methodStr.contains("HashMap<")) {
            imports.add("java.util.Map");
            imports.add("java.util.HashMap");
        }
        if (methodStr.contains("Pattern") || methodStr.contains("Matcher")) {
            imports.add("java.util.regex.Pattern");
            imports.add("java.util.regex.Matcher");
        }

        return imports;
    }

    /**
     * Get description for utility class.
     */
    private String getUtilityDescription(String className) {
        return switch (className) {
            case "ValidationUtils" -> "validation operations";
            case "StringUtils" -> "string manipulation";
            case "DateUtils" -> "date and time operations";
            case "MathUtils" -> "mathematical calculations";
            default -> "common operations";
        };
    }

    /**
     * Update call sites to use utility class (simplified).
     */
    private void updateCallSites(
            CompilationUnit cu,
            MethodDeclaration originalMethod,
            String utilityClassName) {

        String methodName = originalMethod.getNameAsString();

        // Find all method calls to this method
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (call.getNameAsString().equals(methodName)) {
                // Replace with UtilityClass.method() call
                call.setScope(new NameExpr(utilityClassName));
            }
        });

        // Add import for utility class
        String packageName = getPackageName(cu);
        cu.addImport(packageName + ".util." + utilityClassName);

        // Remove the original method
        originalMethod.remove();
    }

    /**
     * Result of a refactoring operation.
     */
    public record RefactoringResult(
            Path sourceFile,
            String refactoredCode,
            String utilityClassName) {

        /**
         * Write the utility class to a file.
         */
        public void applyToUtility(Path targetDirectory) throws IOException {
            Path utilityFile = targetDirectory.resolve(utilityClassName + ".java");
            Files.writeString(utilityFile, refactoredCode);
        }
    }
}
