package com.raditha.dedup.lsh;

import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LSHIndexTest {

    @Test
    void testAddAndQueryExactMatch() {
        MinHash minHash = new MinHash(100, 3);
        // 50 bands * 2 rows = 100 hash functions (aligned)
        LSHIndex index = new LSHIndex(minHash, 50, 2);

        List<String> tokens = Arrays.asList("A", "B", "C", "D", "E");
        StatementSequence seq = new StatementSequence(null, null, 0, null, null, null); // Dummy seq

        index.add(tokens, seq);
        Set<StatementSequence> result = index.query(tokens);

        assertTrue(result.contains(seq), "Should retrieve exact match");
    }

    @Test
    void testQueryFindsSimilar() {
        MinHash minHash = new MinHash(100, 3);
        LSHIndex index = new LSHIndex(minHash, 50, 2);

        // Highly similar tokens
        List<String> tokens1 = Arrays.asList("A", "B", "C", "D", "E", "F", "G");
        List<String> tokens2 = Arrays.asList("A", "B", "C", "D", "E", "F", "H"); // One diff

        StatementSequence seq1 = new StatementSequence(null, null, 0, null, null, null);

        index.add(tokens1, seq1);

        // Query with slightly different tokens
        Set<StatementSequence> result = index.query(tokens2);

        // With 50 bands, probability of at least one band colliding for high Jaccard is
        // very high
        assertTrue(result.contains(seq1), "Should retrieve similar match via collision");
    }

    @Test
    void testQueryNoMatchForDifferent() {
        MinHash minHash = new MinHash(100, 3);
        LSHIndex index = new LSHIndex(minHash, 50, 2);

        List<String> tokens1 = Arrays.asList("A", "B", "C", "D");
        List<String> tokens2 = Arrays.asList("X", "Y", "Z", "W");

        StatementSequence seq1 = new StatementSequence(null, null, 0, null, null, null);

        index.add(tokens1, seq1);
        Set<StatementSequence> result = index.query(tokens2);

        assertFalse(result.contains(seq1), "Should not retrieve distinct item");
    }
}
