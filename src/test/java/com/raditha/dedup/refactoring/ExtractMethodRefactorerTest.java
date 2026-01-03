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

import static org.junit.jupiter.api.Assertions.*;

class ExtractMethodRefactorerTest {

        private DuplicationAnalyzer analyzer;
        private RefactoringEngine engine;

        @TempDir
        Path tempDir;

        @BeforeEach
        void setUp() {
                // Use lenient config to ensure small code blocks are detected
                analyzer = new DuplicationAnalyzer(DuplicationConfig.lenient());
        }

        @Test
        void testMultipleSameTypeParameters() throws IOException {
                Path testBedFile = Path
                                .of("test-bed/src/main/java/com/raditha/bertie/testbed/wrongarguments/MultipleStringParams.java");
                assertTrue(Files.exists(testBedFile), "Test bed file should exist: " + testBedFile.toAbsolutePath());

                String codeWithDuplicates = Files.readString(testBedFile);

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
                System.setIn(new java.io.ByteArrayInputStream("y\n".getBytes()));

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
                System.out.println("Refactored Code:\n" + refactoredCode);

                // ASSERTION FOR THE BUG:
                // The refactored code MUST contain the original email literals in the method
                // calls.
                // If the bug occurs, "bob@example.com" might be replaced by "Bob" or lost.
                assertTrue(refactoredCode.contains("\"bob@example.com\""),
                                "Refactored code should preserve the specific email literal for Bob in the method call");

                assertTrue(refactoredCode.contains("\"alice@example.com\""),
                                "Refactored code should preserve the specific email literal for Alice in the method call");

                // Verification:
                // 1. Should have introduced a helper method
                // 2. The helper method should take 3 args (String, String, int) - order may
                // vary
                // 3. The original methods should call this helper
                // 4. CRITICALLY: The calls should pass the correct literals in correct order

                // This assertion checks if the parameters were correctly identified and
                // substituted.
                // If the bug exists, we might see it failing to use the parameters in the
                // extracted method
                // or creating multiple methods if the reuse logic failed (though here we only
                // have 1 cluster so 1 method).
                // The real bug manifests as: inside the extracted method, does it use the
                // parameters?

                assertTrue(refactoredCode.contains("processAlice()"), "Should preserve processAlice");
                assertTrue(refactoredCode.contains("processBob()"), "Should preserve processBob");

                // Check for the method call with correct args
                // Note: The method name is generated (e.g., "extractedMethod"), we need to find
                // it.
                String methodName = recommendation.suggestedMethodName();
                assertTrue(refactoredCode.contains(methodName + "("), "Should call the extracted method");

                // Check if the extracted method uses the parameters
                // It should NOT contain hardcoded "Alice" or "Bob"
                int aliceCount = refactoredCode.split("\"Alice\"").length - 1;
                int bobCount = refactoredCode.split("\"Bob\"").length - 1;

                // Original code had "Alice" once in processAlice
                // Refactored code should have "Alice" passed as argument in processAlice call
                // But the extracted method DEFINITION should NOT have "Alice".

                // If "Alice" appears in the extracted method, substitution failed.
                // We can check if the parameter names are used in the body.

                // For the correct behavior, we expect:
                // extractedMethod(String name, String email, ...) { ... "Processing " + name
                // ... }

                // If bug is present, it might look like:
                // extractedMethod(String name, String email, ...) { ... "Processing " + "Alice"
                // ... }
                // OR it substituted the WRONG string variable.
        }
}
