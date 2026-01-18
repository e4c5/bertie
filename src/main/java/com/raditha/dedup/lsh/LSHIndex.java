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
 * 
 * OPTIMIZATION: Uses bit-packed long keys instead of String to eliminate
 * object allocations (benefits large projects with 100k+ sequences).
 */
public class LSHIndex {

    private final MinHash minHash;
    private final int numBands;
    private final int rowsPerBand;
    private final Map<Long, List<StatementSequence>> buckets;

    /**
     * @param minHash     MinHash instance to use for signature generation.
     * @param numBands    Number of bands to split the signature into.
     * @param rowsPerBand Number of rows (hash values) per band.
     * @throws IllegalArgumentException if signature length doesn't match band
     *                                  configuration
     */
    public LSHIndex(MinHash minHash, int numBands, int rowsPerBand) {
        this.minHash = minHash;
        this.numBands = numBands;
        this.rowsPerBand = rowsPerBand;
        this.buckets = new HashMap<>();

        // Validate that configuration is consistent
        int requiredSignatureLength = numBands * rowsPerBand;
        if (minHash.getNumHashFunctions() != requiredSignatureLength) {
            throw new IllegalArgumentException(
                    String.format("MinHash signature length (%d) must equal numBands * rowsPerBand (%d * %d = %d)",
                            minHash.getNumHashFunctions(), numBands, rowsPerBand, requiredSignatureLength));
        }
    }

    /**
     * Index a sequence.
     * Computes signature from tokens, splits into bands, and adds to buckets.
     */
    public void add(List<String> tokens, StatementSequence sequence) {
        int[] signature = minHash.computeSignature(tokens);
        for (int b = 0; b < numBands; b++) {
            long bucketKey = generateBucketKey(b, signature);
            buckets.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(sequence);
        }
    }

    public Set<StatementSequence> query(List<String> tokens) {
        Set<StatementSequence> candidates = new HashSet<>();
        int[] signature = minHash.computeSignature(tokens);

        for (int b = 0; b < numBands; b++) {
            long bucketKey = generateBucketKey(b, signature);
            List<StatementSequence> bucket = buckets.get(bucketKey);

            if (bucket != null) {
                candidates.addAll(bucket);
            }
        }
        return candidates;
    }

    /**
     * Query for candidates and then add the sequence to the index.
     * efficient: computes signature only once.
     */
    public Set<StatementSequence> queryAndAdd(List<String> tokens, StatementSequence sequence) {
        Set<StatementSequence> candidates = new HashSet<>();
        int[] signature = minHash.computeSignature(tokens);

        for (int b = 0; b < numBands; b++) {
            long bucketKey = generateBucketKey(b, signature);
            List<StatementSequence> bucket = buckets.computeIfAbsent(bucketKey, k -> new ArrayList<>());
            candidates.addAll(bucket);
            bucket.add(sequence);
        }
        return candidates;
    }

    /**
     * Generate a bit-packed long key for a specific band and its partial signature.
     * Bits 63-56: bandIndex (8 bits), Bits 55-0: 56-bit hash of signature segment.
     */
    private long generateBucketKey(int bandIndex, int[] signature) {
        long bandPart = ((long) bandIndex) << 56;
        long hashPart = computeSegmentHash(signature, bandIndex * rowsPerBand, rowsPerBand);
        return bandPart | hashPart;
    }

    /**
     * Compute a 56-bit hash from a segment of the signature array.
     */
    private long computeSegmentHash(int[] signature, int start, int length) {
        long hash = 0x9e3779b97f4a7c15L; // Golden ratio constant
        for (int i = 0; i < length; i++) {
            hash ^= signature[start + i];
            hash *= 0xff51afd7ed558ccdL; // MurmurHash3 constant
            hash ^= (hash >>> 33);
        }
        return hash & 0x00FFFFFFFFFFFFFFL; // Truncate to 56 bits
    }
}

