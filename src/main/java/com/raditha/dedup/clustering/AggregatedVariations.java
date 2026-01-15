package com.raditha.dedup.clustering;

import com.raditha.dedup.model.ExprInfo;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.model.VariableReference;
import com.raditha.dedup.model.VaryingExpression;

import java.util.Map;
import java.util.Set;

/**
 * Holds the aggregated variation analysis results from comparing
 * a primary sequence against all duplicates in a cluster.
 */
public record AggregatedVariations(
    Map<Integer, VaryingExpression> uniqueVariations,
    Set<VariableReference> variableReferences,
    Set<String> declaredInternalVariables,
    Map<Integer, Map<StatementSequence, ExprInfo>> exprBindings
) {}
