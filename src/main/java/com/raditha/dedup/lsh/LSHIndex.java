package com.raditha.dedup.lsh;

import com.raditha.dedup.model.StatementSequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LSH Index using the Banding technique.
 * Maps MinHash signatures to buckets to identify candidate pairs with high
 * probability.
 */
public class LSHIndex {

    private final MinHash minHash;
    private final int numBands;
    private final int rowsPerBand;
    private final Map<String, List<StatementSequence>> buckets;

    /**
     * @param minHash     MinHash instance to use for signature generation.
     * @param numBands    Number of bands to split the signature into.
     * @param rowsPerBand Number of rows (hash values) per band.
     */
    public LSHIndex(MinHash minHash, int numBands, int rowsPerBand) {
        this.minHash = minHash;
        this.numBands = numBands;
        this.rowsPerBand = rowsPerBand;
        this.buckets = new HashMap<>();
    }

    /**
     * Index a sequence.
     * Computes signature from tokens, splits into bands, and adds to buckets.
     */
    public void add(List<String> tokens, StatementSequence sequence) {
        int[] signature = minHash.computeSignature(tokens);
        for (int b = 0; b < numBands; b++) {
            String bucketKey = generateBucketKey(b, signature);
            buckets.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(sequence);
        }
    }

    public Set<StatementSequence> query(List<String> tokens) {
        Set<StatementSequence> candidates = new HashSet<>();
        int[] signature = minHash.computeSignature(tokens);

        for (int b = 0; b < numBands; b++) {
            String bucketKey = generateBucketKey(b, signature);
            List<StatementSequence> bucket = buckets.get(bucketKey);

            if (bucket != null) {
                candidates.addAll(bucket);
            }
        }
        return candidates;
    }

    /**
     * Generate a unique key for a specific band and its partial signature.
     * Key format: "bandIndex:hash1,hash2,..."
     */
    private String generateBucketKey(int bandIndex, int[] signature) {
        StringBuilder sb = new StringBuilder();
        sb.append(bandIndex).append(":");
        int startIndex = bandIndex * rowsPerBand;
        for (int r = 0; r < rowsPerBand; r++) {
            if (r > 0)
                sb.append(",");
            sb.append(signature[startIndex + r]);
        }
        return sb.toString();
    }
}
