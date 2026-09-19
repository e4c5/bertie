package com.raditha.dedup.similarity;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.normalization.ASTNormalizer;
import com.raditha.dedup.normalization.NormalizedNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SequenceAlignerTest {

    private final ASTNormalizer normalizer = new ASTNormalizer();
    private final ASTStructuralSimilarity positional = new ASTStructuralSimilarity();

    @Test
    void insertedStatementIsAlignedAsSingleGap() {
        List<NormalizedNode> a = normalize(
                "int x = a + 1;",
                "int y = x * 2;",
                "list.add(y);",
                "count++;");
        List<NormalizedNode> b = normalize(
                "int x = a + 1;",
                "log.info(\"hi\");",
                "int y = x * 2;",
                "list.add(y);",
                "count++;");

        SequenceAligner.Alignment alignment = SequenceAligner.align(a, b);

        assertEquals(5, alignment.length());
        assertEquals(4, alignment.matches());
        assertEquals(1, alignment.gaps());
        assertEquals(a.size(), alignment.left().stream().filter(n -> n != null).count());
        assertEquals(b.size(), alignment.right().stream().filter(n -> n != null).count());
        assertEquals(0.8, alignment.structuralScore(), 1e-9);
        assertTrue(positional.calculate(a, b) < 0.5,
                "positional score collapses after the insertion; alignment must not");
    }

    @Test
    void identicalSequencesHaveNoGaps() {
        List<NormalizedNode> a = normalize("foo();", "bar();");
        SequenceAligner.Alignment alignment = SequenceAligner.align(a, a);
        assertEquals(0, alignment.gaps());
        assertEquals(1.0, alignment.structuralScore(), 1e-9);
    }

    @Test
    void disjointSequencesAreAllGaps() {
        List<NormalizedNode> a = normalize("foo();");
        List<NormalizedNode> b = normalize("int x = 1;");
        SequenceAligner.Alignment alignment = SequenceAligner.align(a, b);
        assertEquals(0, alignment.matches());
        assertEquals(2, alignment.gaps());
        assertEquals(0.0, alignment.structuralScore(), 1e-9);
    }

    private List<NormalizedNode> normalize(String... stmts) {
        List<Statement> parsed = Arrays.stream(stmts).map(StaticJavaParser::parseStatement).toList();
        return normalizer.normalize(parsed);
    }
}
