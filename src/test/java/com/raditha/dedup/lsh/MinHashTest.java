package com.raditha.dedup.lsh;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MinHashTest {

    @Test
    void testComputeSignatureDeterminism() {
        MinHash minHash = new MinHash(100, 3);
        List<String> tokens = Arrays.asList("A", "B", "C", "D", "E");

        int[] sig1 = minHash.computeSignature(tokens);
        int[] sig2 = minHash.computeSignature(tokens);

        assertNotNull(sig1);
        assertEquals(100, sig1.length);
        assertArrayEquals(sig1, sig2, "Signature must be deterministic");
    }

    @Test
    void testSimilarityHigh() {
        MinHash minHash = new MinHash(100, 3);
        // Longer sequences to ensure stability of shingling
        // Generating ~40 tokens
        String base = "public void testMethod() { int a = 0; int b = 1; int c = a + b; System.out.println(c); } ";
        String longString1 = base.repeat(5);
        String longString2 = base.repeat(4)
                + "public void testMethod() { int a = 0; int b = 1; int c = a + b; System.out.println(CHANGE); } ";

        List<String> tokens1 = Arrays.asList(longString1.split(" "));
        List<String> tokens2 = Arrays.asList(longString2.split(" "));

        int[] sig1 = minHash.computeSignature(tokens1);
        int[] sig2 = minHash.computeSignature(tokens2);

        double similarity = minHash.estimateSimilarity(sig1, sig2);
        assertTrue(similarity > 0.8, "Similarity should be high for nearly identical sequences. Found: " + similarity);
    }

    @Test
    void testSimilarityLow() {
        MinHash minHash = new MinHash(100, 3);
        // Different lists
        List<String> tokens1 = Arrays.asList("int", "a", "=", "1", ";");
        List<String> tokens2 = Arrays.asList("String", "b", "=", "hello", ";");

        int[] sig1 = minHash.computeSignature(tokens1);
        int[] sig2 = minHash.computeSignature(tokens2);

        double similarity = minHash.estimateSimilarity(sig1, sig2);
        assertTrue(similarity < 0.2, "Similarity should be low for different sequences. Found: " + similarity);
    }

    @Test
    void testIdenticalSimilarity() {
        MinHash minHash = new MinHash(100, 3);
        List<String> tokens = Arrays.asList("A", "B", "C", "D");

        int[] sig1 = minHash.computeSignature(tokens);

        double similarity = minHash.estimateSimilarity(sig1, sig1);
        assertEquals(1.0, similarity, 0.001, "Similarity of identical object should be 1.0");
    }
}
