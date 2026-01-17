package com.raditha.dedup.extraction;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StatementExtractorNestedBlockTest {

    @Test
    void testTryCatchBlockExtraction() {
        String code = """
                package com.test;
                import java.io.IOException;
                
                public class TestClass {
                    public String method1(String data) {
                        String result = null;
                        try {
                            String normalized = data.trim();
                            result = "Processed: " + normalized;
                        } catch (IOException e) {
                            result = "Error: " + e.getMessage();
                        }
                        return result;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        StatementExtractor extractor = new StatementExtractor(2); // min 2 statements to see all
        List<StatementSequence> sequences = extractor.extractSequences(cu, Path.of("Test.java"));

        // Print all sequences for debugging
        System.out.println("Total sequences extracted: " + sequences.size());
        for (int i = 0; i < sequences.size(); i++) {
            StatementSequence seq = sequences.get(i);
            System.out.println("\nSequence #" + (i + 1) + ":");
            System.out.println("  Statements: " + seq.statements().size());
            System.out.println("  Lines: " + seq.range().startLine() + "-" + seq.range().endLine());
            System.out.println("  Content:");
            seq.statements().forEach(stmt -> System.out.println("    " + stmt));
        }

        // We should extract:
        // 1. Full method body: [result=null, try-catch, return] - 3 statements
        // 2. Try block: [normalized=..., result=...] - 2 statements (if min is 2)
        // 3. Maybe catch block: [result=...] - 1 statement (below min)

        assertTrue(sequences.size() >= 1, "Should extract at least the full method body");
        
        // Find the sequence that contains the full method body (3 statements at top level)
        boolean foundFullMethodBody = sequences.stream()
                .anyMatch(seq -> seq.statements().size() == 3);
        
        assertTrue(foundFullMethodBody, "Should extract the full method body as a sequence");
    }
}
