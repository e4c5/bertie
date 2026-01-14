package com.raditha.dedup.lsh;

import java.util.List;
import java.util.Set;

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
     * optimized to avoid string concatenation.
     */
    private Set<Integer> generateShingleHashes(List<String> tokens) {
        // assume tokens are already trimmed by tokenizer

        java.util.HashSet<Integer> shingleHashes = new java.util.HashSet<>();

        if (tokens.size() <= shingleSize) {
             // Fallback for short sequences: combine all tokens
             shingleHashes.add(combineHashes(tokens, 0, tokens.size()));
        } else {
            // Sliding window
            for (int i = 0; i <= tokens.size() - shingleSize; i++) {
                shingleHashes.add(combineHashes(tokens, i, shingleSize));
            }
        }

        return shingleHashes;
    }

    /**
     * Combine hashes of a sublist of tokens using a polynomial rolling hash approximation.
     * hash = t1.hashCode() * 31^(k-1) + t2.hashCode() * 31^(k-2) + ...
     */
    private int combineHashes(List<String> tokens, int start, int length) {
        int h = 0;
        for (int i = 0; i < length; i++) {
            h = 31 * h + tokens.get(start + i).hashCode();
        }
        return h;
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

    public int getNumHashFunctions() {
        return numHashFunctions;
    }

}
