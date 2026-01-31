package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConstructorRefactoringTest {

    private MethodExtractor extractor;
    private Path sourcePath;

    @BeforeAll
    static void setupClass() throws IOException {
        File configFile = new File("src/test/resources/analyzer-tests.yml");
        if (configFile.exists()) {
            Settings.loadConfigMap(configFile);
        } else {
            // Fallback for isolated test runs if needed, or fail
            throw new IOException("Config file not found: " + configFile.getAbsolutePath());
        }
        AbstractCompiler.reset();
    }

    @BeforeEach
    void setUp() {
        extractor = new MethodExtractor();
        sourcePath = Paths.get("src/test/resources/TestClass.java");
    }

    @Test
    void testRefactorConstructorDuplicate() {
        String code = """
                class TestClass {
                    public TestClass() {
                        int a = 1;
                        int b = 2;
                        System.out.println(a + b);
                    }

                    public TestClass(int x) {
                        int a = 1;
                        int b = 2;
                        System.out.println(a + b);
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration classDecl = cu.getClassByName("TestClass").orElseThrow();
        ConstructorDeclaration ctor1 = classDecl.getConstructors().get(0);
        ConstructorDeclaration ctor2 = classDecl.getConstructors().get(1);

        // Extract sequences manually
        List<Statement> stmts1 = ctor1.getBody().getStatements();
        StatementSequence seq1 = new StatementSequence(
                stmts1,
                new Range(3, 25, 5, 34),
                0,
                ctor1,
                cu,
                sourcePath
        );

        List<Statement> stmts2 = ctor2.getBody().getStatements();
        StatementSequence seq2 = new StatementSequence(
                stmts2,
                new Range(9, 25, 11, 34),
                0,
                ctor2,
                cu,
                sourcePath
        );

        // Setup Cluster and Recommendation
        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));
        when(cluster.duplicates()).thenReturn(List.of(new SimilarityPair(seq1, seq2, null)));
        when(cluster.getContainingMethods()).thenReturn(Collections.emptySet()); // Only constructors

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_HELPER_METHOD,
                "helperMethod",
                Collections.emptyList(),
                StaticJavaParser.parseType("void"),
                "TestClass",
                1.0,
                3,
                null
        );

        // Execute Refactoring
        MethodExtractor.RefactoringResult result = extractor.refactor(cluster, recommendation);

        // Verify Result
        assertNotNull(result);
        assertEquals(RefactoringStrategy.EXTRACT_HELPER_METHOD, result.strategy());

        // Verify helper method was added
        List<MethodDeclaration> methods = classDecl.getMethods();
        assertEquals(1, methods.size());
        assertEquals("helperMethod", methods.get(0).getNameAsString());
        assertEquals("void", methods.get(0).getType().asString());

        // Verify constructors now call the helper
        assertEquals(1, ctor1.getBody().getStatements().size());
        assertTrue(ctor1.getBody().getStatements().get(0).toString().contains("helperMethod()"));

        assertEquals(1, ctor2.getBody().getStatements().size());
        assertTrue(ctor2.getBody().getStatements().get(0).toString().contains("helperMethod()"));
    }
}
