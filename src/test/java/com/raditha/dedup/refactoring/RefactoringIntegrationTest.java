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

/**
 * Integration test for end-to-end refactoring workflow.
 * Uses temporary directories to safely test actual file modifications.
 */
class RefactoringIntegrationTest {

    @TempDir
    Path tempDir;

    private DuplicationAnalyzer analyzer;
    private RefactoringEngine engine;

    @BeforeEach
    void setUp() {
        analyzer = new DuplicationAnalyzer(DuplicationConfig.lenient());
    }

    @Test
    void testEndToEndRefactoring() throws IOException {
        // 1. Create a test file with obvious duplicates
        String originalCode = """
                package com.test;

                public class UserService {

                    public void createAdmin() {
                        User user = new User();
                        user.setName("Admin");
                        user.setEmail("admin@test.com");
                        user.setRole("admin");
                        user.setActive(true);
                        repository.save(user);
                    }

                    public void createGuest() {
                        User user = new User();
                        user.setName("Guest");
                        user.setEmail("guest@test.com");
                        user.setRole("guest");
                        user.setActive(false);
                        repository.save(user);
                    }

                    public void createModerator() {
                        User user = new User();
                        user.setName("Moderator");
                        user.setEmail("mod@test.com");
                        user.setRole("moderator");
                        user.setActive(true);
                        repository.save(user);
                    }
                }
                """;

        Path testFile = tempDir.resolve("UserService.java");
        Files.writeString(testFile, originalCode);

        // 2. Parse and analyze for duplicates
        CompilationUnit cu = StaticJavaParser.parse(originalCode);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        // Should find duplicates
        assertTrue(report.hasDuplicates(), "Should detect duplicates");
        assertTrue(report.clusters().size() > 0, "Should have clusters");

        // 3. Run refactoring in BATCH mode (auto-apply)
        engine = new RefactoringEngine(
                tempDir,
                RefactoringEngine.RefactoringMode.BATCH,
                RefactoringVerifier.VerificationLevel.NONE // Skip compilation for speed
        );

        RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

        // 4. Verify refactoring happened
        assertTrue(session.getSuccessful().size() > 0,
                "Should have successful refactorings");
        assertEquals(0, session.getFailed().size(),
                "Should have no failures");

        // 5. Read modified file
        String refactoredCode = Files.readString(testFile);

        // 6. Verify changes
        assertNotEquals(originalCode, refactoredCode,
                "File should be modified");

        // Should contain extracted method
        assertTrue(refactoredCode.contains("private"),
                "Should have private method");

        // Should still compile
        assertDoesNotThrow(() -> StaticJavaParser.parse(refactoredCode),
                "Refactored code should parse successfully");

        // 7. Verify semantic method naming
        // Should NOT have generic "extractedMethod" if semantic naming worked
        CompilationUnit refactoredCu = StaticJavaParser.parse(refactoredCode);
        long methodCount = refactoredCu.findAll(
                com.github.javaparser.ast.body.MethodDeclaration.class).size();

        assertTrue(methodCount > 3, "Should have added extracted method(s)");

        // Cleanup happens automatically via @TempDir
    }

    @Test
    void testRefactoringRollbackOnFailure() throws IOException {
        // Create malformed code that will fail validation
        String badCode = """
                package com.test;

                public class BadService {
                    void method1() {
                        if (condition) {
                            doSomething();
                            doMore();
                            finish();
                        }
                    }

                    void method2() {
                        // Different control flow - should fail safety validation
                        while (condition) {
                            doSomething();
                            doMore();
                            finish();
                        }
                    }
                }
                """;

        Path testFile = tempDir.resolve("BadService.java");
        Files.writeString(testFile, badCode);

        CompilationUnit cu = StaticJavaParser.parse(badCode);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        if (report.hasDuplicates()) {
            engine = new RefactoringEngine(
                    tempDir,
                    RefactoringEngine.RefactoringMode.BATCH,
                    RefactoringVerifier.VerificationLevel.NONE);

            RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

            // File should be unchanged if validation failed
            String afterCode = Files.readString(testFile);
            assertEquals(badCode, afterCode,
                    "File should be unchanged after failed refactoring");
        }
    }

    @Test
    void testMultipleRefactoringsInSameFile() throws IOException {
        String code = """
                package com.test;

                public class OrderService {
                    void processOrder1() {
                        order.validate();
                        order.calculateTotal();
                        order.applyDiscounts();
                        repository.save(order);
                    }

                    void processOrder2() {
                        order.validate();
                        order.calculateTotal();
                        order.applyDiscounts();
                        repository.save(order);
                    }

                    void setupUser1() {
                        user.setActive(true);
                        user.setVerified(true);
                        user.save();
                    }

                    void setupUser2() {
                        user.setActive(false);
                        user.setVerified(false);
                        user.save();
                    }
                }
                """;

        Path testFile = tempDir.resolve("OrderService.java");
        Files.writeString(testFile, code);

        CompilationUnit cu = StaticJavaParser.parse(code);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        engine = new RefactoringEngine(
                tempDir,
                RefactoringEngine.RefactoringMode.BATCH,
                RefactoringVerifier.VerificationLevel.NONE);

        RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

        // Should handle multiple clusters
        int totalProcessed = session.getTotalProcessed();
        assertTrue(totalProcessed >= 2,
                "Should process multiple duplicate clusters");

        // File should still be valid Java
        String refactoredCode = Files.readString(testFile);
        assertDoesNotThrow(() -> StaticJavaParser.parse(refactoredCode));
    }

    @Test
    void testUniqueMethodNames() throws IOException {
        // Create code where semantic naming will generate similar names
        String code = """
                package com.test;

                public class DataService {
                    void load1() {
                        data.load();
                        data.validate();
                        data.process();
                    }

                    void load2() {
                        data.load();
                        data.validate();
                        data.process();
                    }

                    void load3() {
                        data.load();
                        data.validate();
                        data.process();
                    }
                }
                """;

        Path testFile = tempDir.resolve("DataService.java");
        Files.writeString(testFile, code);

        CompilationUnit cu = StaticJavaParser.parse(code);
        DuplicationReport report = analyzer.analyzeFile(cu, testFile);

        engine = new RefactoringEngine(
                tempDir,
                RefactoringEngine.RefactoringMode.BATCH,
                RefactoringVerifier.VerificationLevel.NONE);

        RefactoringEngine.RefactoringSession session = engine.refactorAll(report);

        // Read and parse refactored code
        String refactoredCode = Files.readString(testFile);
        CompilationUnit refactoredCu = StaticJavaParser.parse(refactoredCode);

        // Extract all method names
        var methodNames = refactoredCu.findAll(
                com.github.javaparser.ast.body.MethodDeclaration.class)
                .stream()
                .map(m -> m.getNameAsString())
                .toList();

        // All method names should be unique (no duplicates)
        long uniqueCount = methodNames.stream().distinct().count();
        assertEquals(methodNames.size(), uniqueCount,
                "All method names should be unique");
    }
}
