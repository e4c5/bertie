package com.raditha.dedup.analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test to understand why live-out variables are not being detected.
 */
class DataFlowAnalyzerDebugTest {

    @Test
    void debugFindVariablesUsedAfter() {
        String code = """
                package test;
                public class Test {
                    public String getUserName1(String userId) {
                        User user = repository.findById(userId);
                        user.setActive(true);
                        user.save();

                        return user.getName();
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        MethodDeclaration method = cu.findFirst(MethodDeclaration.class).orElseThrow();
        BlockStmt body = method.getBody().orElseThrow();
        List<Statement> allStmts = body.getStatements();

        System.out.println("Total statements in method: " + allStmts.size());
        for (int i = 0; i < allStmts.size(); i++) {
            System.out.println("  [" + i + "]: " + allStmts.get(i));
        }

        // Create a sequence of the first 3 statements (lines 0-2)
        List<Statement> duplicateStmts = allStmts.subList(0, 3);
        StatementSequence sequence = new StatementSequence(
                duplicateStmts,
                null,
                0, // startOffset
                method,
                cu,
                Paths.get("Test.java"));

        DataFlowAnalyzer analyzer = new DataFlowAnalyzer();

        // Test findDefinedVariables
        Set<String> defined = analyzer.findDefinedVariables(sequence);
        System.out.println("\nDefined variables: " + defined);

        // Test findVariablesUsedAfter
        Set<String> usedAfter = analyzer.findVariablesUsedAfter(sequence);
        System.out.println("Variables used after: " + usedAfter);

        // Test findLiveOutVariables
        Set<String> liveOut = analyzer.findLiveOutVariables(sequence);
        System.out.println("Live-out variables: " + liveOut);

        // Assertions
        assertTrue(defined.contains("user"), "Should find 'user' as defined");
        assertTrue(usedAfter.contains("user"), "Should find 'user' used after duplicate");
        assertTrue(liveOut.contains("user"), "Should find 'user' as live-out");
    }
}
