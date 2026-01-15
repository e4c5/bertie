package com.raditha.dedup.clustering;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.analysis.ASTParameterExtractor;
import com.raditha.dedup.analysis.DataFlowAnalyzer;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.ExtractionPlan;
import com.raditha.dedup.model.ParameterSpec;
import com.raditha.dedup.model.SimilarityPair;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.model.VariableReference;
import com.raditha.dedup.model.VariationAnalysis;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles all parameter extraction, captured parameters, and filtering logic.
 * Consolidates logic from RefactoringRecommendationGenerator for better testability.
 */
public class ParameterResolver {

    private static final String OBJECT = "Object";

    private final ASTParameterExtractor extractor;
    private final DataFlowAnalyzer dataFlowAnalyzer;
    private final ReturnTypeResolver typeResolver;
    private final Map<String, CompilationUnit> allCUs;

    public ParameterResolver() {
        this(new ASTParameterExtractor(), new DataFlowAnalyzer(), 
             new ReturnTypeResolver(), Collections.emptyMap());
    }

    public ParameterResolver(Map<String, CompilationUnit> allCUs) {
        this(new ASTParameterExtractor(), new DataFlowAnalyzer(), 
             new ReturnTypeResolver(allCUs), allCUs);
    }

    public ParameterResolver(ASTParameterExtractor extractor, DataFlowAnalyzer dataFlowAnalyzer,
                             ReturnTypeResolver typeResolver, Map<String, CompilationUnit> allCUs) {
        this.extractor = extractor;
        this.dataFlowAnalyzer = dataFlowAnalyzer;
        this.typeResolver = typeResolver;
        this.allCUs = allCUs;
    }

    /**
     * Resolve all parameters for the extracted method.
     * 
     * @param analysis The variation analysis
     * @param cluster The duplicate cluster
     * @param validStatementCount The valid statement count (-1 if not truncated)
     * @return List of resolved parameters
     */
    public List<ParameterSpec> resolveParameters(
            VariationAnalysis analysis,
            DuplicateCluster cluster,
            int validStatementCount) {

        // Extract parameters using AST-based extractor
        ExtractionPlan extractionPlan = extractor.extractParameters(analysis);
        List<ParameterSpec> parameters = new ArrayList<>(extractionPlan.parameters());

        // Convert arguments (variable references) to parameters
        for (VariableReference arg : extractionPlan.arguments()) {
            if (parameters.stream().noneMatch(p -> p.getName().equals(arg.name()))) {
                com.github.javaparser.ast.type.Type paramType = convertResolvedTypeToJavaParserType(arg.type());
                parameters.add(new ParameterSpec(
                        arg.name(),
                        paramType,
                        Collections.emptyList(),
                        -1,
                        null, null
                ));
            }
        }

        // Add captured variables
        List<ParameterSpec> capturedParams = identifyCapturedParameters(cluster.primary(), parameters);
        parameters.addAll(capturedParams);

        // Filter out internal parameters
        parameters = filterInternalParameters(parameters, cluster);

        // Refine parameter types
        parameters = refineParameterTypes(parameters, cluster);

        // Filter out parameters from truncated statements
        if (validStatementCount != -1 && validStatementCount < cluster.primary().statements().size()
                && validStatementCount > 0) {
            parameters = filterTruncatedParameters(parameters, cluster.primary(), validStatementCount);
        }

        return parameters;
    }

    private List<ParameterSpec> refineParameterTypes(List<ParameterSpec> parameters, DuplicateCluster cluster) {
        List<ParameterSpec> refined = new ArrayList<>();
        for (ParameterSpec p : parameters) {
            if (OBJECT.equals(p.getType().asString()) && !p.getExampleValues().isEmpty()) {
                String val = p.getExampleValues().get(0);
                String inferred = null;
                try {
                    inferred = typeResolver.findTypeInContext(cluster.primary(), val);
                } catch (Exception e) {
                    // ignore
                }

                if (inferred != null && !inferred.equals(OBJECT)) {
                    refined.add(new ParameterSpec(
                            p.getName(),
                            StaticJavaParser.parseType(inferred),
                            p.getExampleValues(),
                            p.getVariationIndex(),
                            p.getStartLine(),
                            p.getStartColumn()));
                } else {
                    refined.add(p);
                }
            } else {
                refined.add(p);
            }
        }
        return refined;
    }

    private List<ParameterSpec> filterTruncatedParameters(List<ParameterSpec> parameters,
            StatementSequence primary, int validStatementCount) {
        Statement lastAllowedStmt = primary.statements().get(validStatementCount - 1);
        int limitLine = lastAllowedStmt.getEnd().map(p -> p.line).orElse(Integer.MAX_VALUE);

        List<ParameterSpec> filtered = new ArrayList<>();
        for (ParameterSpec p : parameters) {
            if (p.getStartLine() == null || p.getStartLine() <= limitLine) {
                filtered.add(p);
            }
        }
        return filtered;
    }

    private List<ParameterSpec> identifyCapturedParameters(StatementSequence sequence,
            List<ParameterSpec> existingParams) {
        Set<String> usedVars = dataFlowAnalyzer.findVariablesUsedInSequence(sequence);
        Set<String> definedVars = dataFlowAnalyzer.findDefinedVariables(sequence);

        Set<String> capturedVars = new HashSet<>(usedVars);
        capturedVars.removeAll(definedVars);

        Set<String> existingParamNames = new HashSet<>();
        existingParams.forEach(p -> existingParamNames.add(p.getName()));

        List<ParameterSpec> capturedParams = new ArrayList<>();

        CompilationUnit cu = sequence.statements().getFirst().findCompilationUnit().orElse(null);

        // Collect fields from the containing class
        boolean containingMethodIsStatic = false;
        Map<String, FieldInfo> classFields = new HashMap<>();

        var methodOpt = sequence.containingMethod();
        if (methodOpt != null) {
            containingMethodIsStatic = methodOpt.isStatic();
            var classDecl = methodOpt.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                    .orElseThrow();

            classDecl.getFields().forEach(fd -> {
                boolean isStatic = fd.getModifiers().stream()
                        .anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
                fd.getVariables().forEach(
                        v -> classFields.put(v.getNameAsString(), new FieldInfo(v.getType().asString(), isStatic)));
            });
        }

        for (String varName : capturedVars) {
            if (varName == null || varName.isEmpty() || varName.equals("this") || varName.equals("super")
                    || existingParamNames.contains(varName) || Character.isUpperCase(varName.charAt(0)))
                continue;

            if ("System".equals(varName) || "Math".equals(varName) || "Integer".equals(varName)) {
                continue;
            }
            if (cu != null && AbstractCompiler.findType(cu, varName) != null) {
                continue;
            }

            FieldInfo fi = classFields.get(varName);
            if (fi != null) {
                if (!containingMethodIsStatic && fi.isStatic) {
                    String type = fi.type != null ? fi.type : OBJECT;
                    capturedParams.add(new ParameterSpec(varName, StaticJavaParser.parseType(type), 
                            List.of(varName), null, null, null));
                }
                continue;
            }

            String type = typeResolver.findTypeInContext(sequence, varName);
            if ("void".equals(type)) {
                continue;
            }

            capturedParams.add(
                    new ParameterSpec(varName, StaticJavaParser.parseType(type != null ? type : OBJECT),
                            List.of(varName), null, null, null));
        }

        return capturedParams;
    }

    private List<ParameterSpec> filterInternalParameters(List<ParameterSpec> params, DuplicateCluster cluster) {
        Set<String> defined = new HashSet<>(dataFlowAnalyzer.findDefinedVariables(cluster.primary()));

        for (SimilarityPair pair : cluster.duplicates()) {
            defined.addAll(dataFlowAnalyzer.findDefinedVariables(pair.seq2()));
        }

        List<ParameterSpec> filtered = new ArrayList<>();

        CompilationUnit cu = null;
        if (!cluster.primary().statements().isEmpty()) {
            cu = cluster.primary().statements().get(0).findCompilationUnit().orElse(null);
        }

        for (var p : params) {
            if (defined.contains(p.getName())) {
                continue;
            }

            boolean isInternal = false;
            for (String val : p.getExampleValues()) {
                for (String def : defined) {
                    if (val.equals(def) || val.startsWith(def + ".") || val.contains("(" + def + ")")) {
                        isInternal = true;
                        break;
                    }
                }
                if (isInternal) break;

                if (cu != null && val.matches("[A-Z][a-zA-Z0-9_]*")) {
                    try {
                        if (AbstractCompiler.findType(cu, val) != null) {
                            isInternal = true;
                            break;
                        }
                    } catch (Exception e) {
                        // ignored
                    }
                }

                // Check for VOID expressions
                isInternal = checkForVoidExpressions(val, cluster, cu);
                if (isInternal) break;
            }

            if (!isInternal) {
                filtered.add(p);
            }
        }
        return filtered;
    }

    private boolean checkForVoidExpressions(String val, DuplicateCluster cluster, CompilationUnit cu) {
        for (Statement s : cluster.primary().statements()) {
            for (Expression e : s.findAll(Expression.class)) {
                if (e.toString().equals(val)) {
                    try {
                        String type = e.calculateResolvedType().describe();
                        if ("void".equals(type)) {
                            return true;
                        }
                    } catch (Exception ex) {
                        if (e.isMethodCallExpr()) {
                            MethodCallExpr call = e.asMethodCallExpr();
                            if (call.getScope().isPresent()) {
                                String scopeName = call.getScope().get().toString();
                                CompilationUnit typeCU = findCompilationUnit(cluster.primary(), scopeName);

                                if (typeCU != null) {
                                    String methodName = call.getNameAsString();
                                    boolean methodFoundIsVoid = typeCU
                                            .findAll(com.github.javaparser.ast.body.MethodDeclaration.class)
                                            .stream()
                                            .filter(m -> m.getNameAsString().equals(methodName))
                                            .anyMatch(m -> m.getType().isVoidType());

                                    if (methodFoundIsVoid) {
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private CompilationUnit findCompilationUnit(StatementSequence sequence, String scopeName) {
        String scopeType = typeResolver.findTypeInContext(sequence, scopeName);

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

    private com.github.javaparser.ast.type.Type convertResolvedTypeToJavaParserType(
            com.github.javaparser.resolution.types.ResolvedType resolvedType) {
        if (resolvedType == null) {
            return new com.github.javaparser.ast.type.ClassOrInterfaceType(null, OBJECT);
        }

        try {
            String typeDesc = resolvedType.describe();
            return StaticJavaParser.parseType(typeDesc);
        } catch (Exception e) {
            return new com.github.javaparser.ast.type.ClassOrInterfaceType(null, resolvedType.describe());
        }
    }

    // Lightweight holder for field metadata
    private record FieldInfo(String type, boolean isStatic) {}
}
