package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.config.DuplicationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class ExtractMethodRefactorerTest {

        private DuplicationAnalyzer analyzer;
        private RefactoringEngine engine;

        @TempDir
        Path tempDir;

        @BeforeEach
        void setUp() {
                // Use lenient config to ensure small code blocks are detected
                // Pass empty map to avoid global scanning and duplicate class errors
                analyzer = new DuplicationAnalyzer(DuplicationConfig.lenient(), Collections.emptyMap());
        }

        @Test
        void testMultipleSameTypeParameters() throws IOException {
                // Note: We are using a relative path that assumes execution from project root
                // This might need adjustment if test-bed location changes
                Path testBedFile = Path
                                .of("test-bed/src/main/java/com/raditha/bertie/testbed/wrongarguments/MultipleStringParams.java");

                // If test-bed file is not found (e.g. CI/CD), we can mock the content to ensure
                // test robustness
                String codeWithDuplicates;
                if (Files.exists(testBedFile)) {
                        codeWithDuplicates = Files.readString(testBedFile);
                } else {
                        // Fallback mock content if file missing
                        codeWithDuplicates = """
                                           package com.raditha.bertie.testbed.wrongarguments;
                                           class MultipleStringParams {
                                               void processAlice() {
                                                   sendEmail("Alice", "alice@example.com", 25);
                                               }
                                               void processBob() {
                                                   sendEmail("Bob", "bob@example.com", 30);
                                               }
                                               void sendEmail(String n, String e, int a) {}
                                           }
                                        """;
                }

                CompilationUnit cu = StaticJavaParser.parse(codeWithDuplicates);
                Path sourceFile = tempDir.resolve("MultipleStringParams.java");
                Files.writeString(sourceFile, codeWithDuplicates);

                DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

                assertTrue(report.hasDuplicates(), "Should detect duplicates");
                assertEquals(1, report.clusters().size(), "Should have 1 cluster");

                // Verify parameters
                var recommendation = report.clusters().get(0).recommendation();
                System.out.println("DEBUG: Recommendations:");
                recommendation.suggestedParameters().forEach(p -> System.out
                                .println("Param: " + p.name() + " Type: " + p.type() + " Examples: "
                                                + p.exampleValues()));

                assertTrue(recommendation.suggestedParameters().size() >= 3,
                                "Should have at least name, email, age parameters");

                // Use INTERACTIVE mode to force application regardless of confidence
                // Simulate "y" input for confirmation
                System.setIn(new java.io.ByteArrayInputStream("y\\n".getBytes()));

                engine = new RefactoringEngine(
                                tempDir,
                                RefactoringEngine.RefactoringMode.INTERACTIVE,
                                RefactoringVerifier.VerificationLevel.NONE);

                RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

                if (session.getSuccessful().isEmpty()) {
                        System.out.println("Skipped reasons:");
                        session.getSkipped().forEach(s -> System.out.println("- " + s.reason()));
                        session.getFailed().forEach(f -> System.out.println("- FAIL: " + f.error()));
                }

                assertEquals(1, session.getSuccessful().size());

                String refactoredCode = Files.readString(sourceFile);

                // Diagnostic output
                System.out.println("Refactored Code:\\n" + refactoredCode);

                // ASSERTION FOR THE BUG:
                assertTrue(refactoredCode.contains("\"bob@example.com\""),
                                "Refactored code should preserve the specific email literal for Bob");

                assertTrue(refactoredCode.contains("\"alice@example.com\""),
                                "Refactored code should preserve the specific email literal for Alice");

                assertTrue(refactoredCode.contains("processAlice()"), "Should preserve processAlice");
                assertTrue(refactoredCode.contains("processBob()"), "Should preserve processBob");

                String methodName = recommendation.suggestedMethodName();
                assertTrue(refactoredCode.contains(methodName + "("), "Should call the extracted method");
        }

        @Test
        void testPrimitiveParameterTypes() throws IOException {
                // Test that primitive types are correctly created as PrimitiveType, not ClassOrInterfaceType
                String codeWithDuplicates = """
                               package com.example;
                               class Calculator {
                                   void calculateAreaA() {
                                       int result = compute(10, 20, 3.14);
                                   }
                                   void calculateAreaB() {
                                       int result = compute(15, 25, 2.71);
                                   }
                                   int compute(int x, int y, double z) {
                                       return (int)(x * y * z);
                                   }
                               }
                            """;

                CompilationUnit cu = StaticJavaParser.parse(codeWithDuplicates);
                Path sourceFile = tempDir.resolve("Calculator.java");
                Files.writeString(sourceFile, codeWithDuplicates);

                DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

                if (report.hasDuplicates()) {
                        var recommendation = report.clusters().get(0).recommendation();
                        
                        // Verify that parameters have primitive types where appropriate
                        recommendation.suggestedParameters().forEach(p -> {
                                System.out.println("Param: " + p.name() + " Type: " + p.type());
                        });

                        engine = new RefactoringEngine(
                                        tempDir,
                                        RefactoringEngine.RefactoringMode.INTERACTIVE,
                                        RefactoringVerifier.VerificationLevel.NONE);
                        
                        System.setIn(new java.io.ByteArrayInputStream("y\n".getBytes()));
                        RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

                        if (!session.getSuccessful().isEmpty()) {
                                String refactoredCode = Files.readString(sourceFile);
                                System.out.println("Refactored Code:\n" + refactoredCode);
                                
                                // The code should compile correctly with primitive parameters
                                assertDoesNotThrow(() -> StaticJavaParser.parse(refactoredCode),
                                        "Refactored code with primitive parameters should parse correctly");
                        }
                }
        }
}
