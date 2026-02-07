package com.raditha.dedup.clustering;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.Type;
import com.raditha.dedup.analysis.ASTParameterExtractor;
import com.raditha.dedup.analysis.DataFlowAnalyzer;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.ExtractionPlan;
import com.raditha.dedup.model.ParameterSpec;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.model.VariableReference;
import com.raditha.dedup.model.VariationAnalysis;
import com.github.javaparser.resolution.types.ResolvedType;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles all parameter extraction, captured parameters, and filtering logic.
 * Consolidates logic from RefactoringRecommendationGenerator for better testability.
 */
public class ParameterResolver extends AbstractResolver {

    private final ASTParameterExtractor extractor;

    /**
     * Creates a new parameter resolver with default analyzers.
     */
    public ParameterResolver() {
        this(new ASTParameterExtractor(), new DataFlowAnalyzer());
    }

    /**
     * Creates a new parameter resolver with specific components.
     *
     * @param extractor         The parameter extractor
     * @param dataFlowAnalyzer  The data flow analyzer
     */
    public ParameterResolver(ASTParameterExtractor extractor, DataFlowAnalyzer dataFlowAnalyzer) {
        super(dataFlowAnalyzer);
        this.extractor = extractor;
    }

    /**
     * Resolves and filters parameters for the extracted method.
     *
     * @param analysis            The variation analysis result
     * @param cluster             The duplicate cluster
     * @param validStatementCount The valid statement count if truncated (-1 if not)
     * @return List of refined parameter specifications
     */
    public List<ParameterSpec> resolveParameters(
            VariationAnalysis analysis,
            DuplicateCluster cluster,
            int validStatementCount) {

        StatementSequence effectiveSequence = cluster.primary();
        if (validStatementCount > 0 && validStatementCount < cluster.primary().statements().size()) {
             // Replicate truncation logic to ensure we analyze the exact code that will be extracted
             java.util.List<Statement> prefixStmts = cluster.primary().statements().subList(0, validStatementCount);
             com.raditha.dedup.model.Range fullRange = cluster.primary().range();
             
             // Calculate new range end based on last stmt
             Statement last = prefixStmts.get(validStatementCount - 1);
             int endLine = last.getEnd().map(p -> p.line).orElse(fullRange.endLine());
             int endColumn = last.getEnd().map(p -> p.column).orElse(fullRange.endColumn());
             
             com.raditha.dedup.model.Range prefixRange = new com.raditha.dedup.model.Range(
                 fullRange.startLine(), fullRange.startColumn(), endLine, endColumn);
                 
             effectiveSequence = new StatementSequence(
                 prefixStmts, prefixRange, cluster.primary().startOffset(),
                 cluster.primary().containingCallable(), cluster.primary().compilationUnit(),
                 cluster.primary().sourceFilePath());
        }

        DataFlowAnalyzer.SequenceAnalysis primaryAnalysis = dataFlowAnalyzer.analyzeSequenceVariables(effectiveSequence);

        ExtractionPlan extractionPlan = extractor.extractParameters(analysis);
        List<ParameterSpec> parameters = new ArrayList<>(extractionPlan.parameters());

        addArgumentsAsParameters(extractionPlan, parameters);

        if (validStatementCount != -1 && validStatementCount < cluster.primary().statements().size()
                && validStatementCount > 0) {
            parameters = filterTruncatedParameters(parameters, cluster.primary(), validStatementCount);
        }

        List<ParameterSpec> capturedParams = identifyCapturedParameters(effectiveSequence, parameters, primaryAnalysis);
        parameters.addAll(capturedParams);

        parameters = filterInternalParameters(parameters, cluster, primaryAnalysis);

        return refineParameterTypes(parameters, cluster);
    }

    private void addArgumentsAsParameters(ExtractionPlan extractionPlan, List<ParameterSpec> parameters) {
        for (VariableReference arg : extractionPlan.arguments()) {
            if (parameters.stream().noneMatch(p -> p.getName().equals(arg.name()))) {
                Type paramType = convertResolvedTypeToJavaParserType(arg.type());
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
            List<ParameterSpec> existingParams, DataFlowAnalyzer.SequenceAnalysis analysis) {
        Set<String> usedVars = analysis.usedVars();
        Set<String> definedVars = analysis.definedVars();

        Set<String> capturedVars = new HashSet<>(usedVars);
        capturedVars.removeAll(definedVars);

        Set<String> existingParamNames = new HashSet<>();
        existingParams.forEach(p -> existingParamNames.add(p.getName()));

        CompilationUnit cu = sequence.statements().getFirst().findCompilationUnit().orElse(null);
        Map<String, FieldInfo> classFields = getFieldInfoMap(sequence);
        boolean containingMethodIsStatic = isContainingMethodStatic(sequence);

        List<ParameterSpec> capturedParams = new ArrayList<>();
        for (String varName : capturedVars) {
            if (!shouldSkipCapturedVariable(varName, existingParamNames, cu) && 
                !processFieldCapture(varName, classFields, containingMethodIsStatic, capturedParams)) {
                
                Type type = findTypeInContext(sequence, varName);
                if (!type.isVoidType()) {
                    capturedParams.add(new ParameterSpec(varName, type,
                            List.of(varName), null, null, null));
                }
            }
        }

        return capturedParams;
    }

    private boolean shouldSkipCapturedVariable(String varName, Set<String> existingParamNames, CompilationUnit cu) {
        if (varName == null || varName.isEmpty()) {
             return true;
        }
        if (varName.equals("this") || varName.equals("super")) {
             return true;
        }
        if (existingParamNames.contains(varName)) {
             return true;
        }
        
        if (Character.isUpperCase(varName.charAt(0))) {
            if (Set.of("System", "Math", "Integer", "String", "Double", "Long", "Boolean", OBJECT).contains(varName)) {
                return true;
            }
            return  (cu != null && AbstractCompiler.findType(cu, varName) != null);
        }

        return false;
    }

    private boolean processFieldCapture(String varName, Map<String, FieldInfo> classFields, 
            boolean containingMethodIsStatic, List<ParameterSpec> capturedParams) {
        FieldInfo fi = classFields.get(varName);
        if (fi != null) {
            if (!containingMethodIsStatic && fi.isStatic) {
                capturedParams.add(new ParameterSpec(varName, fi.type,
                        List.of(varName), null, null, null));
            }
            return true;
        }
        return false;
    }

    private boolean isContainingMethodStatic(StatementSequence sequence) {
        // Handle different container types
        switch (sequence.containerType()) {
            case STATIC_INITIALIZER:
                return true;  // Static initializers are always in static context
            case INSTANCE_INITIALIZER:
                return false;  // Instance initializers are always in instance context
            case LAMBDA:
                // For lambdas, walk up the AST to find the enclosing callable
                if (sequence.container() instanceof com.github.javaparser.ast.expr.LambdaExpr lambda) {
                    var enclosingCallable = lambda.findAncestor(com.github.javaparser.ast.body.CallableDeclaration.class);
                    if (enclosingCallable.isPresent() && enclosingCallable.get() instanceof MethodDeclaration m) {
                        return m.isStatic();
                    }
                    // If we can't find an enclosing callable, assume non-static
                    return false;
                }
                return false;
            case METHOD:
                var methodOpt = sequence.containingCallable();
                if (methodOpt instanceof MethodDeclaration m) {
                    return m.isStatic();
                }
                return false;
            case CONSTRUCTOR:
                return false;  // Constructors are never static
            default:
                return false;
        }
    }

    private Map<String, FieldInfo> getFieldInfoMap(StatementSequence sequence) {
        Map<String, FieldInfo> classFields = new HashMap<>();
        
        // Find the enclosing class declaration from the container node
        var classDecl = sequence.container().findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class);
        
        classDecl.ifPresent(decl -> decl.getFields().forEach(fd -> {
            boolean isStatic = fd.getModifiers().stream()
                    .anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
            fd.getVariables().forEach(v -> 
                classFields.put(v.getNameAsString(), new FieldInfo(resolveTypeToAST(v.getType(), v, sequence), isStatic)));
        }));
        
        return classFields;
    }

    private List<ParameterSpec> filterInternalParameters(List<ParameterSpec> params, DuplicateCluster cluster, 
            DataFlowAnalyzer.SequenceAnalysis primaryAnalysis) {
        Set<String> defined = new HashSet<>(primaryAnalysis.definedVars());

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
        if (isDefinedVariable(val, defined) || isStaticClassReference(val, cu)) {
            return true;
        }

        return isVoidExpression(val, cluster);
    }

    private boolean isDefinedVariable(String val, Set<String> defined) {
        if (defined.contains(val)) {
            return true;
        }
        
        for (String def : defined) {
            if (val.startsWith(def + ".")) {
                return true;
            }
        }
        return false;
    }

    private boolean isStaticClassReference(String val, CompilationUnit cu) {
        if (cu != null && val.matches("[A-Z]\\w*")) {
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
        class VoidExpressionVisitor extends com.github.javaparser.ast.visitor.GenericVisitorAdapter<Boolean, String> {
            @Override
            public Boolean visit(com.github.javaparser.ast.expr.MethodCallExpr e, String targetVal) {
                if (e.toString().equals(targetVal) && isExpressionVoid(e, cluster)) {
                    return true;
                }
                return super.visit(e, targetVal);
            }
        }
        
        VoidExpressionVisitor visitor = new VoidExpressionVisitor();
        for (Statement s : cluster.primary().statements()) {
            if (Boolean.TRUE.equals(s.accept(visitor, val))) {
                return true;
            }
        }
        return false;
    }

    private boolean isExpressionVoid(Expression e, DuplicateCluster cluster) {
        try {
            ResolvedType type = e.calculateResolvedType();
            if (type.isVoid()) {
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
            Type inferred = null;
            try {
                inferred = findTypeInContext(cluster.primary(), val);
            } catch (Exception e) {
                // ignore
            }

            if (inferred != null && !inferred.asString().equals(OBJECT)) {
                return new ParameterSpec(
                        p.getName(),
                        inferred,
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

    private record FieldInfo(Type type, boolean isStatic) {}
}
