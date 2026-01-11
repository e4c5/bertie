package com.raditha.dedup.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Analysis of variations between two similar code sequences.
 * Categorizes differences by type for parameter extraction.
 */
public class VariationAnalysis {

    // AST-based fields (New)
    private final List<VaryingExpression> varyingExpressions;
    private final Set<VariableReference> variableReferences;
    private final Set<String> declaredInternalVariables; // NEW

    // Legacy fields (Token-based)
    private final List<Variation> variations;
    private final boolean hasControlFlowDifferences;

    private VariationAnalysis(Builder builder) {
        this.varyingExpressions = builder.varyingExpressions != null ? builder.varyingExpressions
                : Collections.emptyList();
        this.variableReferences = builder.variableReferences != null ? builder.variableReferences
                : Collections.emptySet();
        this.declaredInternalVariables = builder.declaredInternalVariables != null ? builder.declaredInternalVariables
                : Collections.emptySet();

        this.variations = builder.variations != null ? builder.variations : Collections.emptyList();
        this.hasControlFlowDifferences = builder.hasControlFlowDifferences;
    }

    public static Builder builder() {
        return new Builder();
    }

    // Accessors

    public List<VaryingExpression> getVaryingExpressions() {
        return varyingExpressions;
    }

    // Alias for backward compatibility if needed, though we migrate to getters
    // usually
    public List<VaryingExpression> varyingExpressions() {
        return varyingExpressions;
    }

    public Set<VariableReference> variableReferences() {
        return variableReferences;
    }

    public Set<String> getDeclaredInternalVariables() {
        return declaredInternalVariables;
    }

    public List<Variation> getVariations() {
        return variations;
    }

    public boolean hasControlFlowDifferences() {
        return hasControlFlowDifferences;
    }

    public boolean hasVariations() {
        return !variations.isEmpty() || hasControlFlowDifferences;
    }

    public int getVariationCount() {
        return variations.size();
    }

    public boolean canParameterize() {
        if (hasControlFlowDifferences)
            return false;
        if (variations.size() > 5)
            return false;
        return variations.stream().allMatch(Variation::canParameterize);
    }

    // Builder Class

    public static class Builder {
        private List<VaryingExpression> varyingExpressions;
        private Set<VariableReference> variableReferences;
        private Set<String> declaredInternalVariables;
        private List<Variation> variations;
        private boolean hasControlFlowDifferences;
        private Map<Integer, Map<StatementSequence, String>> valueBindings;
        private Map<Integer, Map<StatementSequence, ExprInfo>> exprBindings;

        public Builder varyingExpressions(List<VaryingExpression> varyingExpressions) {
            this.varyingExpressions = varyingExpressions;
            return this;
        }

        public Builder variableReferences(Set<VariableReference> variableReferences) {
            this.variableReferences = variableReferences;
            return this;
        }

        public Builder declaredInternalVariables(Set<String> declaredInternalVariables) {
            this.declaredInternalVariables = declaredInternalVariables;
            return this;
        }

        public Builder variations(List<Variation> variations) {
            this.variations = variations;
            return this;
        }

        public Builder exprBindings(Map<Integer, Map<StatementSequence, ExprInfo>> exprBindings) {
            this.exprBindings = exprBindings;
            return this;
        }

        public VariationAnalysis build() {
            return new VariationAnalysis(this);
        }
    }
}
