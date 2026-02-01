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

    /**
     * Create a new builder for VariationAnalysis.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    // Accessors

    /**
     * Gets the list of varying expressions found during analysis.
     *
     * @return list of varying expressions
     */
    public List<VaryingExpression> getVaryingExpressions() {
        return varyingExpressions;
    }

    /**
     * Gets the list of varying expressions (alias).
     *
     * @return list of varying expressions
     */
    public List<VaryingExpression> varyingExpressions() {
        return varyingExpressions;
    }

    /**
     * Gets the set of variable references found in the sequence.
     *
     * @return set of variable references
     */
    public Set<VariableReference> variableReferences() {
        return variableReferences;
    }

    /**
     * Gets the set of variables declared internally within the sequence.
     *
     * @return set of internal variable names
     */
    public Set<String> getDeclaredInternalVariables() {
        return declaredInternalVariables;
    }

    /**
     * Gets the legacy variations list.
     *
     * @return list of variations
     */
    public List<Variation> getVariations() {
        return variations;
    }

    /**
     * Checks if there are control flow differences.
     *
     * @return true if control flow differs
     */
    public boolean hasControlFlowDifferences() {
        return hasControlFlowDifferences;
    }

    /**
     * Checks if any variations exist.
     *
     * @return true if there are variations or control flow differences
     */
    public boolean hasVariations() {
        return !variations.isEmpty() || hasControlFlowDifferences;
    }

    /**
     * Gets the number of variations.
     *
     * @return variation count
     */
    public int getVariationCount() {
        return variations.size();
    }

    /**
     * Builder for VariationAnalysis.
     */
    public static class Builder {
        private List<VaryingExpression> varyingExpressions;
        private Set<VariableReference> variableReferences;
        private Set<String> declaredInternalVariables;
        private List<Variation> variations;
        private boolean hasControlFlowDifferences;
        private Map<Integer, Map<StatementSequence, String>> valueBindings;
        private Map<Integer, Map<StatementSequence, ExprInfo>> exprBindings;

        /**
         * Sets the varying expressions.
         */
        public Builder varyingExpressions(List<VaryingExpression> varyingExpressions) {
            this.varyingExpressions = varyingExpressions;
            return this;
        }

        /**
         * Sets the variable references.
         */
        public Builder variableReferences(Set<VariableReference> variableReferences) {
            this.variableReferences = variableReferences;
            return this;
        }

        /**
         * Sets the declared internal variables.
         */
        public Builder declaredInternalVariables(Set<String> declaredInternalVariables) {
            this.declaredInternalVariables = declaredInternalVariables;
            return this;
        }

        /**
         * Sets the variations.
         */
        public Builder variations(List<Variation> variations) {
            this.variations = variations;
            return this;
        }


         /**
          * Sets whether there are control flow differences between the sequences.
          *
          * @param hasControlFlowDifferences true if control flow differs, false otherwise
          * @return this builder for chaining
          */
         public Builder hasControlFlowDifferences(boolean hasControlFlowDifferences) {
             this.hasControlFlowDifferences = hasControlFlowDifferences;
             return this;
         }
         * Sets the expression bindings.
         */
        public Builder exprBindings(Map<Integer, Map<StatementSequence, ExprInfo>> exprBindings) {
            this.exprBindings = exprBindings;
            return this;
        }

        /**
         * Builds the VariationAnalysis instance.
         */
        public VariationAnalysis build() {
            return new VariationAnalysis(this);
        }
    }
}
