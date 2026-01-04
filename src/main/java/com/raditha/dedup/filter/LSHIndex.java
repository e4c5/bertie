package com.raditha.dedup.filter;

import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.model.Token;
import com.raditha.dedup.model.StatementSequence;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Locality Sensitive Hashing (LSH) index for fast approximate nearest neighbor search.
 * Uses MinHash to estimate Jaccard similarity between sequences of tokens.
 */
public class LSHIndex<T> {

    private final int numHashFunctions;
    private final int numBands;
    private final int rowsPerBand;
    private final int shingleSize;
    private final Map<Integer, List<T>>[] hashTables;
    private final long[][] hashCoefficients; // a and b for (ax + b) % p
    private static final long PRIME = 2147483647L; // Mersenne prime 2^31 - 1

    /**
     * Create an LSH index with default parameters for code duplication detection.
     * Tuned for Jaccard similarity threshold ~0.5.
     */
    public LSHIndex() {
        // 100 hash functions, 20 bands of 5 rows
        // Threshold approx (1/20)^(1/5) = 0.55
        this(100, 20, 3);
    }

    /**
     * Create an LSH index with custom parameters.
     *
     * @param numHashFunctions Total number of hash functions (must be numBands * rowsPerBand)
     * @param numBands Number of bands (L)
     * @param shingleSize Size of k-shingles (k-grams)
     */
    @SuppressWarnings("unchecked")
    public LSHIndex(int numHashFunctions, int numBands, int shingleSize) {
        if (numHashFunctions % numBands != 0) {
            throw new IllegalArgumentException("numHashFunctions must be divisible by numBands");
        }
        this.numHashFunctions = numHashFunctions;
        this.numBands = numBands;
        this.rowsPerBand = numHashFunctions / numBands;
        this.shingleSize = shingleSize;

        this.hashTables = new Map[numBands];
        for (int i = 0; i < numBands; i++) {
            this.hashTables[i] = new HashMap<>();
        }

        this.hashCoefficients = new long[numHashFunctions][2];
        Random rand = new Random(12345); // Fixed seed for reproducibility
        for (int i = 0; i < numHashFunctions; i++) {
            hashCoefficients[i][0] = rand.nextInt((int) PRIME - 1) + 1; // a
            hashCoefficients[i][1] = rand.nextInt((int) PRIME - 1) + 1; // b
        }
    }

    /**
     * Index a list of items.
     *
     * @param items List of items to index
     * @param tokenProvider Function to extract tokens from an item
     */
    public void index(List<T> items, java.util.function.Function<T, List<Token>> tokenProvider) {
        for (T item : items) {
            List<Token> tokens = tokenProvider.apply(item);
            int[] signature = computeMinHashSignature(tokens);
            addToBuckets(item, signature);
        }
    }

    /**
     * Get candidate pairs that might be duplicates.
     *
     * @return Set of unique candidate pairs
     */
    public Set<Pair<T>> getCandidates() {
        Set<Pair<T>> candidates = new HashSet<>();

        for (Map<Integer, List<T>> table : hashTables) {
            for (List<T> bucket : table.values()) {
                if (bucket.size() > 1) {
                    // Generate all pairs in this bucket
                    for (int i = 0; i < bucket.size(); i++) {
                        for (int j = i + 1; j < bucket.size(); j++) {
                            T item1 = bucket.get(i);
                            T item2 = bucket.get(j);

                            // Canonicalize pair order based on system identity hash code
                            // to avoid duplicates like (a,b) and (b,a)
                            if (System.identityHashCode(item1) < System.identityHashCode(item2)) {
                                candidates.add(new Pair<>(item1, item2));
                            } else {
                                candidates.add(new Pair<>(item2, item1));
                            }
                        }
                    }
                }
            }
        }
        return candidates;
    }

    private int[] computeMinHashSignature(List<Token> tokens) {
        int[] signature = new int[numHashFunctions];
        Arrays.fill(signature, Integer.MAX_VALUE);

        Set<Integer> shingleHashes = generateShingleHashes(tokens);

        for (int h : shingleHashes) {
            for (int i = 0; i < numHashFunctions; i++) {
                // h_i(x) = (a*x + b) % p
                long hashVal = (hashCoefficients[i][0] * h + hashCoefficients[i][1]) % PRIME;
                signature[i] = Math.min(signature[i], (int) hashVal);
            }
        }
        return signature;
    }

    private Set<Integer> generateShingleHashes(List<Token> tokens) {
        Set<Integer> hashes = new HashSet<>();
        if (tokens.size() < shingleSize) {
            // If sequence is smaller than shingle size, just hash the whole thing
            hashes.add(hashTokens(tokens));
            return hashes;
        }

        for (int i = 0; i <= tokens.size() - shingleSize; i++) {
            List<Token> shingle = tokens.subList(i, i + shingleSize);
            hashes.add(hashTokens(shingle));
        }
        return hashes;
    }

    private int hashTokens(List<Token> tokens) {
        CRC32 crc = new CRC32();
        for (Token t : tokens) {
            // Hash normalized value to ensure similar code hashes to same value
            crc.update(t.normalizedValue().getBytes());
        }
        return (int) crc.getValue();
    }

    private void addToBuckets(T item, int[] signature) {
        for (int band = 0; band < numBands; band++) {
            int start = band * rowsPerBand;
            int end = start + rowsPerBand;

            // Combine hash values in this band to form a bucket key
            int bucketKey = Arrays.hashCode(Arrays.copyOfRange(signature, start, end));

            hashTables[band].computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(item);
        }
    }

    /**
     * Simple Pair record for internal use
     */
    public record Pair<T>(T first, T second) {}
}
