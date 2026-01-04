package com.raditha.dedup.filter;

import com.raditha.dedup.model.Token;
import com.raditha.dedup.model.TokenType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LSHIndexTest {

    @Test
    void testExactMatch() {
        LSHIndex<String> index = new LSHIndex<>(100, 20, 3);

        List<String> items = List.of("A", "B");

        // Define tokens for A and B such that they are identical
        index.index(items, item -> {
            List<Token> tokens = new ArrayList<>();
            tokens.add(new Token(TokenType.VAR, "VAR", "x"));
            tokens.add(new Token(TokenType.OPERATOR, "OPERATOR(=)", "="));
            tokens.add(new Token(TokenType.INT_LIT, "INT_LIT", "1"));
            return tokens;
        });

        Set<LSHIndex.Pair<String>> candidates = index.getCandidates();
        assertEquals(1, candidates.size());
        LSHIndex.Pair<String> pair = candidates.iterator().next();
        assertTrue((pair.first().equals("A") && pair.second().equals("B")) ||
                   (pair.first().equals("B") && pair.second().equals("A")));
    }

    @Test
    void testNoMatch() {
        LSHIndex<String> index = new LSHIndex<>(100, 20, 3);

        List<String> items = List.of("A", "B");

        index.index(items, item -> {
            List<Token> tokens = new ArrayList<>();
            if (item.equals("A")) {
                // Sequence A: x = 1; (Assignment)
                tokens.add(new Token(TokenType.VAR, "VAR", "x"));
                tokens.add(new Token(TokenType.OPERATOR, "OPERATOR(=)", "="));
                tokens.add(new Token(TokenType.INT_LIT, "INT_LIT", "1"));
                tokens.add(new Token(TokenType.OPERATOR, "OPERATOR(;)", ";"));
            } else {
                // Sequence B: if (true) { } (Control Flow)
                // Structurally very different
                tokens.add(new Token(TokenType.CONTROL_FLOW, "CONTROL_FLOW(if)", "if"));
                tokens.add(new Token(TokenType.OPERATOR, "OPERATOR((", "("));
                tokens.add(new Token(TokenType.BOOLEAN_LIT, "BOOLEAN_LIT", "true"));
                tokens.add(new Token(TokenType.OPERATOR, "OPERATOR())", ")"));
                tokens.add(new Token(TokenType.OPERATOR, "OPERATOR({)", "{"));
                tokens.add(new Token(TokenType.OPERATOR, "OPERATOR(})", "}"));
            }
            return tokens;
        });

        Set<LSHIndex.Pair<String>> candidates = index.getCandidates();
        assertEquals(0, candidates.size());
    }

    @Test
    void testNearMatch() {
        LSHIndex<String> index = new LSHIndex<>(100, 20, 3);

        List<String> items = List.of("A", "B");

        // A: x = 1; y = 2;
        // B: x = 1; y = 3; (High Jaccard)

        index.index(items, item -> {
            List<Token> tokens = new ArrayList<>();
            tokens.add(new Token(TokenType.VAR, "VAR", "x"));
            tokens.add(new Token(TokenType.OPERATOR, "OPERATOR(=)", "="));
            tokens.add(new Token(TokenType.INT_LIT, "INT_LIT", "1"));
            tokens.add(new Token(TokenType.OPERATOR, "OPERATOR(;)", ";"));
            tokens.add(new Token(TokenType.VAR, "VAR", "y"));
            tokens.add(new Token(TokenType.OPERATOR, "OPERATOR(=)", "="));
            if (item.equals("A")) {
                tokens.add(new Token(TokenType.INT_LIT, "INT_LIT", "2"));
            } else {
                tokens.add(new Token(TokenType.INT_LIT, "INT_LIT", "3"));
            }
            tokens.add(new Token(TokenType.OPERATOR, "OPERATOR(;)", ";"));
            return tokens;
        });

        Set<LSHIndex.Pair<String>> candidates = index.getCandidates();
        // They should likely match given high similarity
        assertFalse(candidates.isEmpty());
    }
}
