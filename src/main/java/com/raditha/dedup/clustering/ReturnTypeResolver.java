package com.raditha.dedup.clustering;

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
                    returnType = VOID_TYPE;
                }
            }
        } else {
            // Default return logic
            ReturnTypeResult result = determineReturnTypeWithVariable(cluster);
            returnType = result.returnType();
            primaryReturnVariable = result.returnVariable();
        }

        // Additional check: if returnType is set but returnVariable is still null
        if (primaryReturnVariable == null && returnType != null && !returnType.isVoidType()) {
            primaryReturnVariable = dataFlowAnalyzer.findReturnVariable(cluster.primary(), returnType);
        }

        return new ReturnTypeResult(returnType != null ? returnType : VOID_TYPE, primaryReturnVariable);
    }


    private ReturnTypeResult determineReturnTypeWithVariable(DuplicateCluster cluster) {
        Set<Type> returnTypes = new HashSet<>();
        ReturnTypeResult primaryResult = analyzeReturnTypeForSequenceExtended(cluster.primary());

        if (primaryResult.returnType() != null) {
            returnTypes.add(primaryResult.returnType());
        }

        // Check all duplicates
        for (SimilarityPair pair : cluster.duplicates()) {
            StatementSequence duplicate = pair.seq2();
            Type type = analyzeReturnTypeForSequenceExtended(duplicate).returnType();
            if (type != null) {
                returnTypes.add(type);
            }
        }

        if (returnTypes.isEmpty()) {
            return new ReturnTypeResult(VOID_TYPE, null);
        }

        Type unifiedType = unifyTypes(returnTypes);
        return new ReturnTypeResult(unifiedType, primaryResult.returnVariable());
    }

    private Type unifyTypes(Set<Type> returnTypes) {
        // Preference: Non-primitive types first (excluding String and Void)
        for (Type t : returnTypes) {
            if (!t.isPrimitiveType() && !t.isVoidType() && !t.equals(STRING_TYPE)) {
                return t;
            }
        }
        
        // Handle String
        if (returnTypes.contains(STRING_TYPE)) return STRING_TYPE;

        // Handle common numeric types (Widening)
        if (returnTypes.contains(DOUBLE_TYPE)) return DOUBLE_TYPE;
        if (returnTypes.contains(LONG_TYPE)) return LONG_TYPE;
        if (returnTypes.contains(INT_TYPE)) return INT_TYPE;

        return returnTypes.iterator().next();
    }

    private ReturnTypeResult analyzeReturnTypeForSequenceExtended(StatementSequence sequence) {
        DataFlowAnalyzer.SequenceAnalysis analysis = dataFlowAnalyzer.analyzeSequenceVariables(sequence);
        
        // Priority 1: Check for live-out variables
        // Pass a dummy void type if we don't know the type yet
        String returnVarName = dataFlowAnalyzer.findReturnVariable(sequence, VOID_TYPE, analysis);

        if (returnVarName != null) {
            Type type = analysis.typeMap().get(returnVarName);
            if (type != null) {
                return new ReturnTypeResult(resolveTypeToAST(type, sequence.statements().get(0), sequence), returnVarName);
            }

            Type varType = findTypeInContext(sequence, returnVarName);
            if (varType != null && !varType.isVoidType()) {
                return new ReturnTypeResult(varType, returnVarName);
            }
        }

        // Priority 2: Check for logical return statements
        return new ReturnTypeResult(analyzeReturnStatementType(sequence), null);
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
