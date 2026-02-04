package com.raditha.dedup.extraction;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConstructorExtractionTest {

    @Test
    void testExtractFromConstructors() throws Exception {
        Path sourcePath = Paths.get("src/test/java/com/raditha/bertie/testbed_aux/ConstructorDuplicates.java");
        CompilationUnit cu = StaticJavaParser.parse(sourcePath);

        StatementExtractor extractor = new StatementExtractor(3, 5, false);
        cu.setStorage(sourcePath);
        List<StatementSequence> sequences = extractor.extractSequences(cu);

        assertFalse(sequences.isEmpty(), "Should extract sequences");

        boolean foundConstructorSequence = sequences.stream()
                .anyMatch(seq -> seq.containingCallable() instanceof ConstructorDeclaration);

        assertTrue(foundConstructorSequence, "Should find sequences in constructors");

        // Verify specific counts if possible, or just presence
        long constructorSeqs = sequences.stream()
                .filter(seq -> seq.containingCallable() instanceof ConstructorDeclaration)
                .count();

        assertTrue(constructorSeqs > 0);

        sequences.forEach(seq -> {
            assertNotNull(seq.containingCallable());
            if (seq.containingCallable() instanceof ConstructorDeclaration) {
                // Name should be class name
                assertEquals("ConstructorDuplicates", seq.getMethodName());
                assertTrue(seq.getCallableBody().isPresent(), "Constructor sequence should have a body");
            }
        });
    }
}
