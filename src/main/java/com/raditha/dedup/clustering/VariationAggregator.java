package com.raditha.dedup.clustering;

import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analysis.ASTVariationAnalyzer;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.ExprInfo;
import com.raditha.dedup.model.SimilarityPair;
import com.raditha.dedup.model.StatementSequence;
import com.raditha.dedup.model.VariableReference;
import com.raditha.dedup.model.VariationAnalysis;
import com.raditha.dedup.model.VaryingExpression;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregates and deduplicates variations across all duplicates in a cluster.
 * This extracts logic from the main generateRecommendation method to improve clarity.
 */
public class VariationAggregator {

    private final ASTVariationAnalyzer astAnalyzer;

    /**
     * Creates a new aggregator with a default AST variation analyzer.
     */
    public VariationAggregator() {
        this.astAnalyzer = new ASTVariationAnalyzer();
    }

    /**
     * Creates a new aggregator with a specific AST variation analyzer.
     *
     * @param astAnalyzer the analyzer to use
     */
    public VariationAggregator(ASTVariationAnalyzer astAnalyzer) {
        this.astAnalyzer = astAnalyzer;
    }

    /**
     * Aggregate variations from comparing the primary sequence against all duplicates.
     * 
     * @param cluster The duplicate cluster to analyze
     * @return Aggregated variations including unique variations, variable references, 
     *         internal variables, and expression bindings
     */
    public AggregatedVariations aggregate(DuplicateCluster cluster) {
        StatementSequence primary = cluster.primary();
        CompilationUnit cu = primary.compilationUnit();

        List<VaryingExpression> allVariations = new ArrayList<>();
        Set<VariableReference> allVarRefs = new HashSet<>();
        Set<String> allInternalVars = new HashSet<>();

        if (cluster.duplicates().isEmpty()) {
            // Edge case: compare primary against itself
            VariationAnalysis analysis = astAnalyzer.analyzeVariations(primary, primary, cu);
            allVariations.addAll(analysis.varyingExpressions());
            allVarRefs.addAll(analysis.variableReferences());
            allInternalVars.addAll(analysis.getDeclaredInternalVariables());
        } else {
            // Compare primary against each duplicate
            for (SimilarityPair pair : cluster.duplicates()) {
                StatementSequence duplicate = pair.seq2();
                VariationAnalysis analysis = astAnalyzer.analyzeVariations(primary, duplicate, cu);

                allVariations.addAll(analysis.varyingExpressions());
                allVarRefs.addAll(analysis.variableReferences());
                allInternalVars.addAll(analysis.getDeclaredInternalVariables());
            }
        }

        // Deduplicate variations based on position
        Map<Integer, VaryingExpression> uniqueVariations = new HashMap<>();
        for (VaryingExpression v : allVariations) {
            uniqueVariations.putIfAbsent(v.position(), v);
        }

        // Build expression bindings
        Map<Integer, Map<StatementSequence, ExprInfo>> exprBindings = new HashMap<>();

        // Populate bindings for primary using unique variations
        for (VaryingExpression v : uniqueVariations.values()) {
            exprBindings.computeIfAbsent(v.position(), k -> new HashMap<>())
                    .put(primary, ExprInfo.fromExpression(v.expr1()));
        }

        // Populate bindings for duplicates by re-analyzing
        if (!cluster.duplicates().isEmpty()) {
            for (SimilarityPair pair : cluster.duplicates()) {
                StatementSequence duplicate = pair.seq2();
                VariationAnalysis pairAnalysis = astAnalyzer.analyzeVariations(primary, duplicate, cu);

                for (VaryingExpression v : pairAnalysis.varyingExpressions()) {
                    int pos = v.position();
                    if (uniqueVariations.containsKey(pos)) {
                        exprBindings.computeIfAbsent(pos, k -> new HashMap<>())
                                .put(duplicate, ExprInfo.fromExpression(v.expr2()));
                    }
                }
            }
        }

        return new AggregatedVariations(
                uniqueVariations,
                allVarRefs,
                allInternalVars,
                exprBindings
        );
    }

    /**
     * Build a VariationAnalysis from aggregated results.
     * This provides backward compatibility with existing code.
     */
    public VariationAnalysis buildAnalysis(AggregatedVariations aggregated) {
        return VariationAnalysis.builder()
                .varyingExpressions(new ArrayList<>(aggregated.uniqueVariations().values()))
                .variableReferences(aggregated.variableReferences())
                .declaredInternalVariables(aggregated.declaredInternalVariables())
                .exprBindings(aggregated.exprBindings())
                .build();
    }
}
