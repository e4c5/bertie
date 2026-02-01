package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.StatementSequence;
import sa.com.cloudsolutions.antikythera.depsolver.Graph;
import sa.com.cloudsolutions.antikythera.depsolver.GraphNode;
import sa.com.cloudsolutions.antikythera.depsolver.Resolver;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.parser.Callable;
import sa.com.cloudsolutions.antikythera.parser.MCEWrapper;

import java.util.List;
import java.nio.file.Path;

/**
 * Refactorer that extracts cross-class duplicate methods into a dedicated
 * utility class.
 * Handles multi-file refactoring with utility class creation and call site
 * updates.
 */
public class UtilityClassExtractor extends AbstractExtractor {

    private String utilityClassName;

    /**
     * Extracts duplicates to a utility class.
     *
     * @param cluster The duplicate cluster
     * @param recommendation The refactoring recommendation
     * @return Result of the refactoring operation
     */
    @Override
    public MethodExtractor.RefactoringResult refactor(DuplicateCluster cluster,
                                                      RefactoringRecommendation recommendation) {

        initialize(cluster, recommendation);
        StatementSequence primary = cluster.primary();
        Path sourceFile = primary.sourceFilePath();
        CallableDeclaration<?> callableToExtract = primary.containingCallable();

        validateCanBeStatic(callableToExtract);

        this.utilityClassName = determineUtilityClassName(recommendation.getSuggestedMethodName());

        CompilationUnit utilityCu = createUtilityClass(callableToExtract);
        String utilityFqn = packageName + ".util." + utilityClassName;
        AntikytheraRunTime.addCompilationUnit(utilityFqn, utilityCu);
        Path utilityPath = sourceFile.getParent().resolve("util").resolve(utilityClassName + ".java");
        modifiedFiles.put(utilityPath, utilityCu.toString());

        for (CompilationUnit currentCu : involvedCus) {
            updateCallSitesAndImports(currentCu, callableToExtract);

            List<CallableDeclaration<?>> methods = methodsToRemove.get(currentCu);
            if (methods != null) {
                methods.forEach(CallableDeclaration::remove);
            }

            modifiedFiles.put(cuToPath.get(currentCu), currentCu.toString());
        }

        return new MethodExtractor.RefactoringResult(
                modifiedFiles,
                recommendation.getStrategy(),
                "Extracted to utility class: " + utilityClassName);
    }

    private void validateCanBeStatic(CallableDeclaration<?> method) {
        if (method.isStatic()) {
            return;
        }
        // Constructors are never static but are not supported here anyway due to check above.

        if (!method.findAll(ThisExpr.class).isEmpty()) {
            throw new IllegalArgumentException("Method uses 'this' and cannot be made static.");
        }
    }

    private CompilationUnit createUtilityClass(CallableDeclaration<?> originalMethod) {
        CompilationUnit utilCu = new CompilationUnit();
        utilCu.setPackageDeclaration(packageName + ".util");

        MethodDeclaration newMethod = null;
        if (originalMethod instanceof MethodDeclaration md) {
            newMethod = md.clone();
        } else if (originalMethod instanceof ConstructorDeclaration cd) {
            newMethod = new MethodDeclaration();
            newMethod.setName(recommendation.getSuggestedMethodName());
            newMethod.setParameters(new NodeList<>(cd.getParameters()));
            newMethod.setBody(cd.getBody().clone());
            newMethod.setType("void");
        }

        if (newMethod == null) {
            throw new IllegalArgumentException("Unsupported callable type");
        }

        newMethod.setModifiers(Modifier.Keyword.PUBLIC, Modifier.Keyword.STATIC);

        ClassOrInterfaceDeclaration utilClass = utilCu.addClass(utilityClassName)
                .setJavadocComment("Utility class for " + getUtilityDescription() + ".");

        utilClass.addConstructor(Modifier.Keyword.PRIVATE)
                .setBody(new com.github.javaparser.ast.stmt.BlockStmt()
                        .addStatement(new com.github.javaparser.ast.stmt.ThrowStmt(
                                new com.github.javaparser.ast.expr.ObjectCreationExpr(
                                        null,
                                        new ClassOrInterfaceType(null, "UnsupportedOperationException"),
                                        new NodeList<>(new com.github.javaparser.ast.expr.StringLiteralExpr(
                                                "Utility class"))))));

        utilClass.addMember(newMethod);

        CompilationUnit originalCu = originalMethod.findCompilationUnit()
                .orElseThrow(() -> new IllegalStateException("Original method not part of a CompilationUnit"));
        copyNeededImports(originalCu, utilCu, newMethod);

        return utilCu;
    }

    private boolean isUnqualifiedOrThisScoped(MethodCallExpr call) {
        return call.getScope().isEmpty() ||
                call.getScope().map(s -> s instanceof ThisExpr).orElse(false);
    }

    private void updateCallSitesAndImports(CompilationUnit cu, CallableDeclaration<?> originalMethod) {
        String methodName = originalMethod.getNameAsString();
        if (originalMethod instanceof ConstructorDeclaration) {
            methodName = recommendation.getSuggestedMethodName();
        }
        String utilityRunTimePackage = packageName + ".util";
        cu.addImport(utilityRunTimePackage + "." + utilityClassName);

        ClassOrInterfaceDeclaration typeDecl = cu.findFirst(ClassOrInterfaceDeclaration.class).orElse(null);
        if (typeDecl == null) {
            return;
        }
        GraphNode contextNode = Graph.createGraphNode(typeDecl);

        final String finalMethodName = methodName;
        final ClassOrInterfaceDeclaration finalTypeDecl = typeDecl;
        cu.findAll(MethodCallExpr.class).forEach(call -> {
            if (call.getNameAsString().equals(finalMethodName) && isUnqualifiedOrThisScoped(call)) {
                MCEWrapper wrapper = Resolver.resolveArgumentTypes(contextNode, call);
                Callable callable = AbstractCompiler.findCallableDeclaration(wrapper, finalTypeDecl).orElse(null);

                if (callable != null && callable.isMethodDeclaration()) {
                    MethodDeclaration resolvedMethod = callable.asMethodDeclaration();
                    if (resolvedMethod.getSignature().equals(originalMethod.getSignature())) {
                        call.setScope(new NameExpr(utilityClassName));
                    }
                }
            }
        });
    }

    private String getUtilityDescription() {
        return switch (utilityClassName) {
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
}
