package com.raditha.dedup.lsh;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MinHash implementation for estimating Jaccard similarity between statement
 * sequences.
 * Uses k-shingling (token n-grams) and multiple hash functions to generate
 * signatures.
 */
public class MinHash {

    private final int numHashFunctions;
    private final int shingleSize;
    private final long[] seeds;

    /**
     * @param numHashFunctions Number of hash functions (signatures dimension).
     * @param shingleSize      Size of n-grams (k-shingles).
     */
    public MinHash(int numHashFunctions, int shingleSize) {
        this.numHashFunctions = numHashFunctions;
        this.shingleSize = shingleSize;
        this.seeds = generateSeeds(numHashFunctions);
    }

    /**
     * Generate a MinHash signature for a list of tokens.
     */
    public int[] computeSignature(List<String> tokens) {
        // 1. Generate shingles (token n-grams)
        Set<Integer> shingles = generateShingleHashes(tokens);

        // 2. Compute min-hash for each seed
        int[] signature = new int[numHashFunctions];

        for (int i = 0; i < numHashFunctions; i++) {
            long seed = seeds[i];
            int minHash = Integer.MAX_VALUE;

            for (int shingleHash : shingles) {
                // Apply a universal hash function based on the seed
                // h(x) = (a*x + b) % large_prime
                // Simplified here using simple XOR/multiplication for speed,
                // or specific hash mixing.
                int hash = hash(shingleHash, seed);
                if (hash < minHash) {
                    minHash = hash;
                }
            }
            signature[i] = minHash;
        }

        return signature;
    }

    /**
     * Generate k-shingles from the token stream.
     */
    private Set<Integer> generateShingleHashes(List<String> tokens) {
        // Normalize tokens (trim) if not already done? Assuming caller handles it.
        // But let's trim just in case or assume processed.
        List<String> processedTokens = tokens.stream()
                .map(String::trim)
                .collect(Collectors.toList());

        List<String> shingles = new ArrayList<>();
        if (processedTokens.size() <= shingleSize) {
            shingles.add(String.join(" ", processedTokens));
        } else {
            for (int i = 0; i <= processedTokens.size() - shingleSize; i++) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < shingleSize; j++) {
                    if (j > 0)
                        sb.append(" ");
                    sb.append(processedTokens.get(i + j));
                }
                shingles.add(sb.toString());
            }
        }

        return shingles.stream().map(String::hashCode).collect(Collectors.toSet());
    }

    private long[] generateSeeds(int n) {
        long[] s = new long[n];
        for (int i = 0; i < n; i++) {
            s[i] = i * 2654435761L + 0x9e3779b9; // Some large primes / mixing constants
        }
        return s;
    }

    private int hash(int value, long seed) {
        // simple mixing function
        long h = value ^ seed;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return (int) h;
    }

    /**
     * Estimate Jaccard similarity between two signatures.
     */
    public double estimateSimilarity(int[] sig1, int[] sig2) {
        if (sig1.length != sig2.length) {
            throw new IllegalArgumentException("Signatures must have same length");
        }
        int matches = 0;
        for (int i = 0; i < sig1.length; i++) {
            if (sig1[i] == sig2[i]) {
                matches++;
            }
        }
        return (double) matches / sig1.length;
    }
}
