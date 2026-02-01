package com.raditha.dedup.clustering;

import com.raditha.dedup.analysis.DataFlowAnalyzer;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.ParameterSpec;
import com.raditha.dedup.model.StatementSequence;

import java.util.List;
import java.util.Set;

/**
 * Calculates confidence scores for refactoring recommendations.
 * Higher confidence indicates a safer, more likely successful refactoring.
 */
public class RefactoringConfidenceCalculator {

    private final DataFlowAnalyzer dataFlowAnalyzer;
    private final SequenceTruncator truncator;

    /**
     * Creates a new confidence calculator with default analyzers.
     */
    public RefactoringConfidenceCalculator() {
        this.dataFlowAnalyzer = new DataFlowAnalyzer();
        this.truncator = new SequenceTruncator();
    }

    /**
     * Calculate confidence score for a refactoring recommendation.
     * 
     * @param cluster The duplicate cluster
     * @param parameters The extracted parameters
     * @param validStatementCount The number of valid statements (-1 if not truncated)
     * @return Confidence score between 0.0 and 1.0
     */
    public double calculate(
            DuplicateCluster cluster,
            List<ParameterSpec> parameters,
            int validStatementCount) {

        double score = 1.0;

        // Penalty for too many parameters (harder to use)
        if (parameters.size() > 5) {
            score *= 0.7;
        }

        // Penalty for low similarity
        if (cluster.getAverageSimilarity() < 0.85) {
            score *= 0.8;
        }

        // Check for multiple live-out variables (which cannot be returned)
        StatementSequence sequenceToAnalyze = cluster.primary();
        if (validStatementCount != -1) {
            sequenceToAnalyze = truncator.createPrefixSequence(cluster.primary(), validStatementCount);
        }

        Set<String> liveOuts = dataFlowAnalyzer.findLiveOutVariables(sequenceToAnalyze);
        if (liveOuts.size() > 1) {
            // Returning multiple values requires a wrapper object, which we don't support yet
            // Penalize heavily to skip this cluster in batch mode
            score *= 0.1;
        }

        return score;
    }
}
