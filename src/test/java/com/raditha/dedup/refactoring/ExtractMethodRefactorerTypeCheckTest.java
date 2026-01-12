package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.config.DuplicationConfig;
import com.raditha.dedup.model.ParameterSpec;
import com.raditha.dedup.model.RefactoringRecommendation;
import com.raditha.dedup.model.RefactoringStrategy;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

class ExtractMethodRefactorerTypeCheckTest {
    @TempDir
    Path tempDir;
    private DuplicationAnalyzer analyzer;

    @BeforeAll()
    static void setupClass() throws IOException {
        File configFile = new File("src/test/resources/analyzer-tests.yml");
        if (configFile.exists()) {
             Settings.loadConfigMap(configFile);
        } else {
            Settings.loadConfigMap(new File("test-bed/antikythera-test-helper/src/main/resources/analyzer-tests.yml"));
        }

        AntikytheraRunTime.resetAll();
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @BeforeEach
    void setUp() {
        analyzer = new DuplicationAnalyzer(DuplicationConfig.lenient(), Collections.emptyMap());
    }

    @Test
    void testVariableTypeMismatch_FloatVsStringVariable() throws IOException {
        String code = """
                package com.example;
                public class TypeMismatchVar {
                    public void method1() {
                        float f = 1.0f;
                        System.out.println(f);
                    }
                    public void method2() {
                        String s = "hello";
                        System.out.println(s);
                    }
                }
                """;

        CompilationUnit cu = com.github.javaparser.StaticJavaParser.parse(code);
        Path sourceFile = tempDir.resolve("TypeMismatchVar.java");
        Files.writeString(sourceFile, cu.toString());

        var method1 = cu.getClassByName("TypeMismatchVar").get().getMethodsByName("method1").get(0);
        var method2 = cu.getClassByName("TypeMismatchVar").get().getMethodsByName("method2").get(0);

        var stmts1 = method1.getBody().get().getStatements();
        var stmts2 = method2.getBody().get().getStatements();

        // System.out.println(f); is at line 5
        var seq1 = new StatementSequence(
            new com.github.javaparser.ast.NodeList<>(stmts1.get(1)),
            new com.raditha.dedup.model.Range(5, 9, 5, 30),
            0, method1, cu, sourceFile
        );

        // System.out.println(s); is at line 9
        var seq2 = new StatementSequence(
            new com.github.javaparser.ast.NodeList<>(stmts2.get(1)),
            new com.raditha.dedup.model.Range(9, 9, 9, 30),
            0, method2, cu, sourceFile
        );

        // Param 'f' usage is at line 5, col 28
        ParameterSpec param = new ParameterSpec(
            "p0",
            com.github.javaparser.StaticJavaParser.parseType("float"),
            List.of("f"),
            0,
            5, 28
        );

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_HELPER_METHOD,
                "extractedMethod",
                List.of(param),
                "void",
                null,
                1.0,
                1);

         var simResult = new com.raditha.dedup.model.SimilarityResult(
            1.0, 1.0, 1.0, 1.0, 0, 0, null, null, true
        );
        var cluster = new com.raditha.dedup.model.DuplicateCluster(
                seq1,
                List.of(
                    new com.raditha.dedup.model.SimilarityPair(seq1, seq1, simResult),
                    new com.raditha.dedup.model.SimilarityPair(seq1, seq2, simResult)
                ),
                recommendation,
                100);

        ExtractMethodRefactorer refactorer = new ExtractMethodRefactorer();
        ExtractMethodRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        if (!result.modifiedFiles().isEmpty()) {
             System.out.println("Refactored Code (Var):\n" + result.modifiedFiles().values().iterator().next());
             fail("Refactoring should have been aborted due to type mismatch (float parameter vs String variable)");
        }
    }

    @Test
    void testLiteralTypeMismatch_FloatWrapperVsStringLiteral() throws IOException {
         String code = """
                package com.example;
                public class TypeMismatchWrapper {
                    public void method1() {
                        Float f = 1.0f;
                        System.out.println(f);
                    }
                    public void method2() {
                        System.out.println("hello");
                    }
                }
                """;

        CompilationUnit cu = com.github.javaparser.StaticJavaParser.parse(code);
        Path sourceFile = tempDir.resolve("TypeMismatchWrapper.java");
        Files.writeString(sourceFile, cu.toString());

        var method1 = cu.getClassByName("TypeMismatchWrapper").get().getMethodsByName("method1").get(0);
        var method2 = cu.getClassByName("TypeMismatchWrapper").get().getMethodsByName("method2").get(0);

        var stmts1 = method1.getBody().get().getStatements();
        var stmts2 = method2.getBody().get().getStatements();

        // System.out.println(f); is at line 5
        var seq1 = new StatementSequence(
            new com.github.javaparser.ast.NodeList<>(stmts1.get(1)),
            new com.raditha.dedup.model.Range(5, 9, 5, 30),
            0, method1, cu, sourceFile
        );

        // System.out.println("hello"); is at line 8
        var seq2 = new StatementSequence(
            new com.github.javaparser.ast.NodeList<>(stmts2.get(0)),
            new com.raditha.dedup.model.Range(8, 9, 8, 35),
            0, method2, cu, sourceFile
        );

        // Param 'f' usage is at line 5, col 28
        ParameterSpec param = new ParameterSpec(
            "p0",
            com.github.javaparser.StaticJavaParser.parseType("Float"),
            List.of("f"),
            0,
            5, 28
        );

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_HELPER_METHOD,
                "extractedMethod",
                List.of(param),
                "void",
                null,
                1.0,
                1);

         var simResult = new com.raditha.dedup.model.SimilarityResult(
            1.0, 1.0, 1.0, 1.0, 0, 0, null, null, true
        );
        var cluster = new com.raditha.dedup.model.DuplicateCluster(
                seq1,
                List.of(
                    new com.raditha.dedup.model.SimilarityPair(seq1, seq1, simResult),
                    new com.raditha.dedup.model.SimilarityPair(seq1, seq2, simResult)
                ),
                recommendation,
                100);

        ExtractMethodRefactorer refactorer = new ExtractMethodRefactorer();
        ExtractMethodRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        if (!result.modifiedFiles().isEmpty()) {
             System.out.println("Refactored Code (Wrapper):\n" + result.modifiedFiles().values().iterator().next());
             fail("Refactoring should have been aborted due to type mismatch (Float wrapper vs String literal)");
        }
    }
}
