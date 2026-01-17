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

    public List<ParameterSpec> resolveParameters(
            VariationAnalysis analysis,
            DuplicateCluster cluster,
            int validStatementCount) {

        ExtractionPlan extractionPlan = extractor.extractParameters(analysis);
        List<ParameterSpec> parameters = new ArrayList<>(extractionPlan.parameters());

        addArgumentsAsParameters(extractionPlan, parameters);

        List<ParameterSpec> capturedParams = identifyCapturedParameters(cluster.primary(), parameters);
        parameters.addAll(capturedParams);

        parameters = filterInternalParameters(parameters, cluster);

        parameters = refineParameterTypes(parameters, cluster);

        if (validStatementCount != -1 && validStatementCount < cluster.primary().statements().size()
                && validStatementCount > 0) {
            parameters = filterTruncatedParameters(parameters, cluster.primary(), validStatementCount);
        }

        return parameters;
    }

    private void addArgumentsAsParameters(ExtractionPlan extractionPlan, List<ParameterSpec> parameters) {
        for (VariableReference arg : extractionPlan.arguments()) {
            if (parameters.stream().noneMatch(p -> p.getName().equals(arg.name()))) {
                com.github.javaparser.ast.type.Type paramType = convertResolvedTypeToJavaParserType(arg.type());
                parameters.add(new ParameterSpec(
                        arg.name(),
                        paramType,
                        List.of(arg.name()),
                        -1,
                        null, null
                ));
            }
        }
    }

    private List<ParameterSpec> identifyCapturedParameters(StatementSequence sequence,
            List<ParameterSpec> existingParams) {
        Set<String> usedVars = dataFlowAnalyzer.findVariablesUsedInSequence(sequence);
        Set<String> definedVars = dataFlowAnalyzer.findDefinedVariables(sequence);

        Set<String> capturedVars = new HashSet<>(usedVars);
        capturedVars.removeAll(definedVars);

        Set<String> existingParamNames = new HashSet<>();
        existingParams.forEach(p -> existingParamNames.add(p.getName()));

        CompilationUnit cu = sequence.statements().getFirst().findCompilationUnit().orElse(null);
        Map<String, FieldInfo> classFields = getFieldInfoMap(sequence);
        boolean containingMethodIsStatic = isContainingMethodStatic(sequence);

        List<ParameterSpec> capturedParams = new ArrayList<>();
        for (String varName : capturedVars) {
            if (shouldSkipCapturedVariable(varName, existingParamNames, cu) ||
                    processFieldCapture(varName, classFields, containingMethodIsStatic, capturedParams)) {
                continue;
            }

            String type = typeResolver.findTypeInContext(sequence, varName);
            if (!"void".equals(type)) {
                capturedParams.add(new ParameterSpec(varName, StaticJavaParser.parseType(type != null ? type : OBJECT),
                        List.of(varName), null, null, null));
            }
        }

        return capturedParams;
    }

    private boolean shouldSkipCapturedVariable(String varName, Set<String> existingParamNames, CompilationUnit cu) {
        if (varName == null || varName.isEmpty() || varName.equals("this") || varName.equals("super")
                || existingParamNames.contains(varName) || Character.isUpperCase(varName.charAt(0))) {
            return true;
        }

        if ("System".equals(varName) || "Math".equals(varName) || "Integer".equals(varName)) {
            return true;
        }

        return cu != null && AbstractCompiler.findType(cu, varName) != null;
    }

    private boolean processFieldCapture(String varName, Map<String, FieldInfo> classFields, 
            boolean containingMethodIsStatic, List<ParameterSpec> capturedParams) {
        FieldInfo fi = classFields.get(varName);
        if (fi != null) {
            if (!containingMethodIsStatic && fi.isStatic) {
                String type = fi.type != null ? fi.type : OBJECT;
                capturedParams.add(new ParameterSpec(varName, StaticJavaParser.parseType(type),
                        List.of(varName), null, null, null));
            }
            return true;
        }
        return false;
    }

    private boolean isContainingMethodStatic(StatementSequence sequence) {
        var methodOpt = sequence.containingMethod();
        return methodOpt != null && methodOpt.isStatic();
    }

    private Map<String, FieldInfo> getFieldInfoMap(StatementSequence sequence) {
        Map<String, FieldInfo> classFields = new HashMap<>();
        var methodOpt = sequence.containingMethod();
        if (methodOpt != null) {
            var classDecl = methodOpt.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
            classDecl.ifPresent(decl -> decl.getFields().forEach(fd -> {
                boolean isStatic = fd.getModifiers().stream()
                        .anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
                fd.getVariables().forEach(v -> 
                    classFields.put(v.getNameAsString(), new FieldInfo(v.getType().asString(), isStatic)));
            }));
        }
        return classFields;
    }

    private List<ParameterSpec> filterInternalParameters(List<ParameterSpec> params, DuplicateCluster cluster) {
        Set<String> defined = new HashSet<>(dataFlowAnalyzer.findDefinedVariables(cluster.primary()));
        for (SimilarityPair pair : cluster.duplicates()) {
            defined.addAll(dataFlowAnalyzer.findDefinedVariables(pair.seq2()));
        }

        CompilationUnit cu = null;
        if (!cluster.primary().statements().isEmpty()) {
            cu = cluster.primary().statements().get(0).findCompilationUnit().orElse(null);
        }

        List<ParameterSpec> filtered = new ArrayList<>();
        for (ParameterSpec p : params) {
            if (!isInternalParameter(p, defined, cluster, cu)) {
                filtered.add(p);
            }
        }
        return filtered;
    }

    private boolean isInternalParameter(ParameterSpec p, Set<String> defined, DuplicateCluster cluster, CompilationUnit cu) {
        if (defined.contains(p.getName())) {
            return true;
        }

        for (String val : p.getExampleValues()) {
            if (isInternalValue(val, defined, cluster, cu)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInternalValue(String val, Set<String> defined, DuplicateCluster cluster, CompilationUnit cu) {
        if (isDefinedVariable(val, defined)) {
            return true;
        }

        if (isStaticClassReference(val, cu)) {
            return true;
        }

        return isVoidExpression(val, cluster);
    }

    private boolean isDefinedVariable(String val, Set<String> defined) {
        // Optimized check: explicit contains check first (covers all identifiers)
        if (defined.contains(val)) {
            return true;
        }
        
        // Scan for structural containment (field access, method calls)
        for (String def : defined) {
            if (val.startsWith(def + ".") || val.contains("(" + def + ")")) {
                return true;
            }
        }
        return false;
    }

    private boolean isStaticClassReference(String val, CompilationUnit cu) {
        if (cu != null && val.matches("[A-Z]\\w*")) { // Use \w for simplified regex
            try {
                if (AbstractCompiler.findType(cu, val) != null) {
                    return true;
                }
            } catch (Exception e) {
                // ignored
            }
        }
        return false;
    }

    private boolean isVoidExpression(String val, DuplicateCluster cluster) {
        for (Statement s : cluster.primary().statements()) {
            for (Expression e : s.findAll(Expression.class)) {
                if (e.toString().equals(val) && isExpressionVoid(e, cluster)) {
                   return true;
                }
            }
        }
        return false;
    }

    private boolean isExpressionVoid(Expression e, DuplicateCluster cluster) {
        try {
            String type = e.calculateResolvedType().describe();
            if ("void".equals(type)) {
                return true;
            }
        } catch (Exception ex) {
            if (e.isMethodCallExpr()) {
                return isMethodCallVoid(e.asMethodCallExpr(), cluster);
            }
        }
        return false;
    }
    
    private boolean isMethodCallVoid(MethodCallExpr call, DuplicateCluster cluster) {
        if (call.getScope().isPresent()) {
            String scopeName = call.getScope().get().toString();
            CompilationUnit typeCU = findCompilationUnit(cluster.primary(), scopeName);

            if (typeCU != null) {
                String methodName = call.getNameAsString();
                return typeCU.findAll(com.github.javaparser.ast.body.MethodDeclaration.class)
                        .stream()
                        .filter(m -> m.getNameAsString().equals(methodName))
                        .anyMatch(m -> m.getType().isVoidType());
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

    private List<ParameterSpec> refineParameterTypes(List<ParameterSpec> parameters, DuplicateCluster cluster) {
        List<ParameterSpec> refined = new ArrayList<>();
        for (ParameterSpec p : parameters) {
            refined.add(refineSingleParameter(p, cluster));
        }
        return refined;
    }

    private ParameterSpec refineSingleParameter(ParameterSpec p, DuplicateCluster cluster) {
        if (OBJECT.equals(p.getType().asString()) && !p.getExampleValues().isEmpty()) {
            String val = p.getExampleValues().get(0);
            String inferred = null;
            try {
                inferred = typeResolver.findTypeInContext(cluster.primary(), val);
            } catch (Exception e) {
                // ignore
            }

            if (inferred != null && !inferred.equals(OBJECT)) {
                return new ParameterSpec(
                        p.getName(),
                        StaticJavaParser.parseType(inferred),
                        p.getExampleValues(),
                        p.getVariationIndex(),
                        p.getStartLine(),
                        p.getStartColumn());
            }
        }
        return p;
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

    private record FieldInfo(String type, boolean isStatic) {}
}
