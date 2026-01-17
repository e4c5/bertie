package com.raditha.dedup.clustering;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.Type;
import com.raditha.dedup.analysis.DataFlowAnalyzer;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.SimilarityPair;
import com.raditha.dedup.model.StatementSequence;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Consolidates all return type inference logic from RefactoringRecommendationGenerator.
 * Handles type resolution from various sources: variable declarations, fields, 
 * method calls, expressions, and data flow analysis.
 */
public class ReturnTypeResolver {

    public static final String STRING = "String";
    public static final String DOUBLE = "double";
    public static final String BOOLEAN = "boolean";
    public static final String OBJECT = "Object";

    private final DataFlowAnalyzer dataFlowAnalyzer;
    private final SequenceTruncator truncator;
    private final Map<String, CompilationUnit> allCUs;

    public ReturnTypeResolver() {
        this(new DataFlowAnalyzer(), new SequenceTruncator(), java.util.Collections.emptyMap());
    }

    public ReturnTypeResolver(Map<String, CompilationUnit> allCUs) {
        this(new DataFlowAnalyzer(), new SequenceTruncator(), allCUs);
    }

    public ReturnTypeResolver(DataFlowAnalyzer dataFlowAnalyzer, SequenceTruncator truncator,
                              Map<String, CompilationUnit> allCUs) {
        this.dataFlowAnalyzer = dataFlowAnalyzer;
        this.truncator = truncator;
        this.allCUs = allCUs;
    }

    /**
     * Resolve the return type for a duplicate cluster.
     * 
     * @param cluster The duplicate cluster
     * @param validStatementCount The valid statement count (-1 if not truncated)
     * @param truncationReturnVar Return variable from truncation analysis (may be null)
     * @return ReturnTypeResult with the type and optional return variable
     */
    public ReturnTypeResult resolve(DuplicateCluster cluster, int validStatementCount, String truncationReturnVar) {
        String returnType;
        String primaryReturnVariable = truncationReturnVar;

        if (validStatementCount != -1) {
            if (primaryReturnVariable != null) {
                String typeStr = findTypeInContext(cluster.primary(), primaryReturnVariable);
                returnType = typeStr != null ? typeStr : OBJECT;
            } else {
                // Fallback: Re-analyze for return variables in the truncated sequence
                StatementSequence prefix = truncator.createPrefixSequence(cluster.primary(), validStatementCount);
                String liveOut = dataFlowAnalyzer.findReturnVariable(prefix, null);
                if (liveOut != null) {
                    primaryReturnVariable = liveOut;
                    String typeStr = findTypeInContext(cluster.primary(), liveOut);
                    returnType = typeStr != null ? typeStr : OBJECT;
                } else {
                    returnType = "void";
                }
            }
        } else {
            // Default return logic
            returnType = determineReturnType(cluster);
            primaryReturnVariable = findReturnVariable(cluster.primary());
        }

        // Additional check: if returnType is set but returnVariable is still null
        if (primaryReturnVariable == null && returnType != null && !"void".equals(returnType)) {
            primaryReturnVariable = dataFlowAnalyzer.findReturnVariable(cluster.primary(), returnType);
        }

        return new ReturnTypeResult(returnType != null ? returnType : "void", primaryReturnVariable);
    }

    /**
     * Determine the return type by analyzing all sequences in the cluster.
     */
    public String determineReturnType(DuplicateCluster cluster) {
        Set<String> returnTypes = new HashSet<>();
        String primaryType = analyzeReturnTypeForSequence(cluster.primary());

        if (primaryType != null) {
            returnTypes.add(primaryType);
        }

        // Check all duplicates
        for (SimilarityPair pair : cluster.duplicates()) {
            StatementSequence duplicate = pair.seq2();
            String type = analyzeReturnTypeForSequence(duplicate);
            if (type != null) {
                returnTypes.add(type);
            }
        }

        if (returnTypes.isEmpty()) {
            return "void";
        }

        // Priority: Domain objects first
        Optional<String> domainType = returnTypes.stream()
                .filter(t -> !t.equals("int") && !t.equals("long") && !t.equals(DOUBLE) &&
                        !t.equals(BOOLEAN) && !t.equals("void") && !t.equals(STRING))
                .findFirst();

        if (domainType.isPresent()) {
            return domainType.get();
        }

        // Primitive unification
        boolean hasInt = returnTypes.contains("int");
        boolean hasLong = returnTypes.contains("long");
        boolean hasDouble = returnTypes.contains(DOUBLE);

        // Heuristic: Check for time-related variables
        boolean hasObject = returnTypes.stream()
                .anyMatch(t -> !t.equals("int") && !t.equals("long") && !t.equals(DOUBLE) &&
                        !t.equals(BOOLEAN) && !t.equals("void"));

        if (!hasObject && !hasLong && !hasDouble) {
            boolean hasTimeVar = cluster.duplicates().stream()
                    .map(d -> d.seq2().statements().toString())
                    .anyMatch(s -> s.contains("timeout") || s.contains("elapsed") || s.contains("Time"));
            if (hasTimeVar) {
                hasLong = true;
            }
        }

        if (hasDouble) return DOUBLE;
        if (hasLong) return "long";
        if (hasInt) return "int";

        return returnTypes.iterator().next();
    }

    private String analyzeReturnTypeForSequence(StatementSequence sequence) {
        // Priority 1: Check for live-out variables
        String returnVarName = dataFlowAnalyzer.findReturnVariable(sequence, "void");

        if (returnVarName != null) {
            String varType = findTypeInContext(sequence, returnVarName);
            if (varType != null && !"void".equals(varType)) {
                return varType;
            }
        }

        // Priority 2: Check for logical return statements
        return analyzeReturnStatementType(sequence);
    }

    private String analyzeReturnStatementType(StatementSequence sequence) {
        for (Statement stmt : sequence.statements()) {
            if (stmt.isReturnStmt()) {
                var returnStmt = stmt.asReturnStmt();
                if (returnStmt.getExpression().isPresent()) {
                    Expression returnExpr = returnStmt.getExpression().get();
                    if (returnExpr.isEnclosedExpr()) {
                        returnExpr = returnExpr.asEnclosedExpr().getInner();
                    }

                    if (returnExpr.isMethodCallExpr()) {
                        return inferTypeFromMethodCall(returnExpr.asMethodCallExpr(), sequence);
                    }

                    if (returnExpr.isNameExpr()) {
                        String varName = returnExpr.asNameExpr().getNameAsString();
                        return findTypeInContext(sequence, varName);
                    }

                    return inferTypeFromExpression(returnExpr);
                }
            }
        }
        return null;
    }

    /**
     * Find the type of a variable or expression in the given context.
     */
    public String findTypeInContext(StatementSequence sequence, String varName) {
        // 1. Scan statements for variable declarations
        for (Statement stmt : sequence.statements()) {
            for (VariableDeclarationExpr vde : stmt.findAll(VariableDeclarationExpr.class)) {
                for (var v : vde.getVariables()) {
                    if (v.getNameAsString().equals(varName)) {
                        return resolveType(v.getType(), v, sequence);
                    }
                }
            }

            // Check Lambda parameters
            for (com.github.javaparser.ast.expr.LambdaExpr lambda : stmt
                    .findAll(com.github.javaparser.ast.expr.LambdaExpr.class)) {
                for (com.github.javaparser.ast.body.Parameter param : lambda.getParameters()) {
                    if (param.getNameAsString().equals(varName)) {
                        if (!param.getType().isUnknownType() && !param.getType().isVarType()) {
                            String resolved = resolveType(param.getType(), lambda, sequence);
                            if (resolved != null) return resolved;
                        }
                        return OBJECT;
                    }
                }
            }
        }

        // 2. Check field declarations in the containing class
        if (sequence.containingMethod() != null) {
            var classDecl = sequence.containingMethod()
                    .findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
            if (classDecl.isPresent()) {
                for (var field : classDecl.get().getFields()) {
                    for (var v : field.getVariables()) {
                        if (v.getNameAsString().equals(varName)) {
                            return resolveType(field.getElementType(), field, sequence);
                        }
                    }
                }
            }
            for (var param : sequence.containingMethod().getParameters()) {
                if (param.getNameAsString().equals(varName)) {
                    return resolveType(param.getType(), param, sequence);
                }
            }
        }

        // 3. Scan method body for variables declared outside the block
        if (sequence.containingMethod() != null && sequence.containingMethod().getBody().isPresent()) {
            for (VariableDeclarationExpr vde : sequence.containingMethod().getBody().get()
                    .findAll(VariableDeclarationExpr.class)) {
                for (var v : vde.getVariables()) {
                    if (v.getNameAsString().equals(varName)) {
                        return resolveType(v.getType(), v, sequence);
                    }
                }
            }
        }

        // 4. Check if it's an expression (e.g., method call)
        if (varName.contains("(") || varName.contains(".")) {

                Expression expr = com.github.javaparser.StaticJavaParser.parseExpression(varName);
                if (expr.isMethodCallExpr()) {
                    var call = expr.asMethodCallExpr();
                    if (call.getScope().isPresent()) {
                        String scopeName = call.getScope().get().toString();
                        CompilationUnit typeCU = findCompilationUnit(sequence, scopeName);

                        if (typeCU != null) {
                            String methodName = call.getNameAsString();
                            var methods = typeCU.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)
                                    .stream()
                                    .filter(m -> m.getNameAsString().equals(methodName))
                                    .toList();

                            if (!methods.isEmpty()) {
                                return resolveType(methods.get(0).getType(), methods.get(0), sequence);
                            }
                        }
                    }
                }
        }

        return null;
    }

    private String inferTypeFromMethodCall(MethodCallExpr methodCall, StatementSequence sequence) {
        // Try resolution first
        try {
            String resolvedType = methodCall.calculateResolvedType().describe();
            if (resolvedType != null && !resolvedType.equals("java.lang.Object") && !resolvedType.equals(OBJECT)) {
                return resolvedType;
            }
        } catch (Exception e) {
            // Ignore
        }

        // Manual lookup via allCUs
        try {
            if (methodCall.getScope().isPresent()) {
                String scopeName = methodCall.getScope().get().toString();
                Optional<Type> genericTypeOpt = extractGenericTypeFromScope(sequence, scopeName);
                String genericTypeParam = genericTypeOpt.map(Node::toString).orElse(null);

                CompilationUnit typeCU = findCompilationUnit(sequence, scopeName);

                if (typeCU != null) {
                    String methodName = methodCall.getNameAsString();
                    var method = typeCU.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).stream()
                            .filter(m -> m.getNameAsString().equals(methodName))
                            .findFirst();

                    if (method.isPresent()) {
                        String returnType = method.get().getType().asString();
                        if (genericTypeParam != null && returnType.equals("T")) {
                            return genericTypeParam;
                        }
                        return returnType;
                    }
                }
            } else {
                // Implicit scope (this)
                if (sequence.containingMethod() != null) {
                    var classDecl = sequence.containingMethod()
                            .findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
                    if (classDecl.isPresent()) {
                        String methodName = methodCall.getNameAsString();
                        var method = classDecl.get().getMethodsByName(methodName).stream().findFirst();
                        if (method.isPresent()) {
                            return method.get().getType().asString();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore errors during manual lookup
        }

        return null;
    }

    private String inferTypeFromExpression(Expression expr) {
        if (expr.isStringLiteralExpr()) return STRING;
        if (expr.isIntegerLiteralExpr() || expr.isLongLiteralExpr()) return "int";
        if (expr.isBooleanLiteralExpr()) return BOOLEAN;
        if (expr.isObjectCreationExpr()) return expr.asObjectCreationExpr().getType().asString();
        if (expr.isBinaryExpr()) return STRING;
        return OBJECT;
    }

    @SuppressWarnings("unchecked")
    private Optional<Type> extractGenericTypeFromScope(StatementSequence sequence, String scopeName) {
        var classDecl = sequence.containingMethod()
                .findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
        if (classDecl.isEmpty()) {
            return Optional.empty();
        }
        for (var field : classDecl.get().getFields()) {
            if (field.getElementType().isClassOrInterfaceType()) {
                var classType = field.getElementType().asClassOrInterfaceType();
                for (var v : field.getVariables()) {
                    if (v.getNameAsString().equals(scopeName) && classType.getTypeArguments().isPresent()) {
                        var typeArgs = classType.getTypeArguments().get();
                        return typeArgs.getFirst();
                    }
                }
            }
        }
        return Optional.empty();
    }

    private CompilationUnit findCompilationUnit(StatementSequence sequence, String scopeName) {
        String scopeType = findTypeInContext(sequence, scopeName);

        CompilationUnit typeCU = allCUs.get(scopeType);
        if (typeCU == null) {
            for (var entry : allCUs.entrySet()) {
                if (entry.getKey().endsWith("." + scopeType) || entry.getKey().equals(scopeType)) {
                    typeCU = entry.getValue();
                    break;
                }
            }
        }
        return typeCU;
    }

    private String resolveType(Type type, Node contextNode, StatementSequence sequence) {
        var classDecl = contextNode.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
        if (classDecl.isPresent()) {
            String fqn = AbstractCompiler.resolveTypeFqn(type, classDecl.get(), null);
            if (fqn != null && !fqn.equals("java.lang.Object") && !fqn.equals(OBJECT)) {
                return simplifyType(fqn);
            }
        }

        String astType = type.asString();
        if ("var".equals(astType)) {
            if (contextNode instanceof VariableDeclarator v && v.getInitializer().isPresent()) {
                var init = v.getInitializer().get();
                if (init.isMethodCallExpr()) {
                    return inferTypeFromMethodCall(init.asMethodCallExpr(), sequence);
                }
                return inferTypeFromExpression(init);
            }
            return OBJECT;
        }
        return astType;
    }

    private String simplifyType(String fqn) {
        if (fqn == null) return null;
        if (fqn.equals("int") || fqn.equals(BOOLEAN) || fqn.equals(DOUBLE) || fqn.equals("void")) return fqn;

        int lastDot = fqn.lastIndexOf('.');
        if (lastDot > 0) {
            return fqn.substring(lastDot + 1);
        }
        return fqn;
    }

    private String findReturnVariable(StatementSequence sequence) {
        return dataFlowAnalyzer.findReturnVariable(sequence, null);
    }
}
