package com.raditha.dedup.clustering;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.Type;
import com.raditha.dedup.analysis.DataFlowAnalyzer;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.ReturnTypeResult;
import com.raditha.dedup.model.SimilarityPair;
import com.raditha.dedup.model.StatementSequence;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Consolidates all return type inference logic.
 * Handles type resolution from various sources: variable declarations, fields, 
 * method calls, expressions, and data flow analysis.
 */
public class ReturnTypeResolver extends AbstractResolver {

    private final SequenceTruncator truncator;

    /**
     * Creates a new return type resolver with default analyzers.
     *
     * @param allCUs Map of all compilation units for type resolution
     */
    public ReturnTypeResolver(Map<String, CompilationUnit> allCUs) {
        this(new DataFlowAnalyzer(), new SequenceTruncator(), allCUs);
    }

    /**
     * Creates a new return type resolver with specific components.
     *
     * @param dataFlowAnalyzer The data flow analyzer
     * @param truncator        The sequence truncator
     * @param allCUs           Map of all compilation units
     */
    public ReturnTypeResolver(DataFlowAnalyzer dataFlowAnalyzer, SequenceTruncator truncator,
                               Map<String, CompilationUnit> allCUs) {
        super(allCUs, dataFlowAnalyzer);
        this.truncator = truncator;
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
        Type returnType;
        String primaryReturnVariable = truncationReturnVar;

        if (validStatementCount != -1) {
            if (primaryReturnVariable != null) {
                returnType = findTypeInContext(cluster.primary(), primaryReturnVariable);
            } else {
                // Fallback: Re-analyze for return variables in the truncated sequence
                StatementSequence prefix = truncator.createPrefixSequence(cluster.primary(), validStatementCount);
                DataFlowAnalyzer.SequenceAnalysis analysis = dataFlowAnalyzer.analyzeSequenceVariables(prefix);
                String liveOut = dataFlowAnalyzer.findReturnVariable(prefix, null, analysis);
                if (liveOut != null) {
                    primaryReturnVariable = liveOut;
                    returnType = findTypeInContext(cluster.primary(), liveOut);
                } else {
                    returnType = StaticJavaParser.parseType("void");
                }
            }
        } else {
            // Default return logic
            InternalReturnType result = determineReturnTypeWithVariable(cluster);
            returnType = result.type();
            primaryReturnVariable = result.variable();
        }

        // Additional check: if returnType is set but returnVariable is still null
        if (primaryReturnVariable == null && returnType != null && !returnType.isVoidType()) {
            primaryReturnVariable = dataFlowAnalyzer.findReturnVariable(cluster.primary(), returnType);
        }

        return new ReturnTypeResult(returnType != null ? returnType : StaticJavaParser.parseType("void"), primaryReturnVariable);
    }

    private record InternalReturnType(Type type, String variable) {}

    private InternalReturnType determineReturnTypeWithVariable(DuplicateCluster cluster) {
        Set<Type> returnTypes = new HashSet<>();
        InternalReturnType primaryResult = analyzeReturnTypeForSequenceExtended(cluster.primary());

        if (primaryResult.type() != null) {
            returnTypes.add(primaryResult.type());
        }

        // Check all duplicates
        for (SimilarityPair pair : cluster.duplicates()) {
            StatementSequence duplicate = pair.seq2();
            Type type = analyzeReturnTypeForSequenceExtended(duplicate).type();
            if (type != null) {
                returnTypes.add(type);
            }
        }

        if (returnTypes.isEmpty()) {
            return new InternalReturnType(StaticJavaParser.parseType("void"), null);
        }

        Type unifiedType = unifyTypes(returnTypes, cluster);
        return new InternalReturnType(unifiedType, primaryResult.variable());
    }

    private Type unifyTypes(Set<Type> returnTypes, DuplicateCluster cluster) {
        // Preference: Non-primitive types first
        for (Type t : returnTypes) {
            if (!t.isPrimitiveType() && !t.isVoidType() && !t.asString().equals("String")) {
                return t;
            }
        }

        // Handle common numeric types
        boolean hasInt = returnTypes.stream().anyMatch(t -> t.asString().equals("int"));
        boolean hasLong = returnTypes.stream().anyMatch(t -> t.asString().equals("long"));
        boolean hasDouble = returnTypes.stream().anyMatch(t -> t.asString().equals("double"));

        if (hasDouble) return StaticJavaParser.parseType("double");
        if (hasLong) return StaticJavaParser.parseType("long");
        if (hasInt) return StaticJavaParser.parseType("int");

        return returnTypes.iterator().next();
    }

    private InternalReturnType analyzeReturnTypeForSequenceExtended(StatementSequence sequence) {
        DataFlowAnalyzer.SequenceAnalysis analysis = dataFlowAnalyzer.analyzeSequenceVariables(sequence);
        
        // Priority 1: Check for live-out variables
        // Pass a dummy void type if we don't know the type yet
        String returnVarName = dataFlowAnalyzer.findReturnVariable(sequence, StaticJavaParser.parseType("void"), analysis);

        if (returnVarName != null) {
            Type type = analysis.typeMap().get(returnVarName);
            if (type != null) {
                return new InternalReturnType(resolveTypeToAST(type, sequence.statements().get(0), sequence), returnVarName);
            }

            Type varType = findTypeInContext(sequence, returnVarName);
            if (varType != null && !varType.isVoidType()) {
                return new InternalReturnType(varType, returnVarName);
            }
        }

        // Priority 2: Check for logical return statements
        return new InternalReturnType(analyzeReturnStatementType(sequence), null);
    }

    private Type analyzeReturnStatementType(StatementSequence sequence) {
        for (Statement stmt : sequence.statements()) {
            if (stmt.isReturnStmt()) {
                var returnStmt = stmt.asReturnStmt();
                if (returnStmt.getExpression().isPresent()) {
                    Expression returnExpr = returnStmt.getExpression().get();
                    return resolveExpressionTypeToAST(returnExpr, sequence);
                }
            }
        }
        return null;
    }
}
