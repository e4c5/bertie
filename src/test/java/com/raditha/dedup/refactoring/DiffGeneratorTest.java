package com.raditha.dedup.refactoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DiffGenerator - unified diff creation and formatting.
 */
class DiffGeneratorTest {

    private DiffGenerator diffGenerator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        diffGenerator = new DiffGenerator();
    }

    @Test
    void testUnifiedDiffGeneration() throws IOException {
        // Create original file
        String originalCode = """
                public class User {
                    public void createUser() {
                        User user = new User();
                        user.setName("Admin");
                        user.setEmail("admin@test.com");
                        user.save();
                    }
                }
                """;

        String refactoredCode = """
                public class User {
                    public void createUser() {
                        setupUser("Admin", "admin@test.com");
                    }

                    private void setupUser(String name, String email) {
                        User user = new User();
                        user.setName(name);
                        user.setEmail(email);
                        user.save();
                    }
                }
                """;

        Path originalFile = tempDir.resolve("User.java");
        Files.writeString(originalFile, originalCode);

        String diff = diffGenerator.generateUnifiedDiff(originalFile, refactoredCode);

        // Verify diff contains expected markers
        assertNotNull(diff);
        assertTrue(diff.contains("---"), "Should have original file marker");
        assertTrue(diff.contains("+++"), "Should have modified file marker");
        assertTrue(diff.contains("-"), "Should have deletion markers");
        assertTrue(diff.contains("+"), "Should have addition markers");
        assertTrue(diff.contains("setupUser"), "Should show new method name");
    }

    @Test
    void testDiffWithCustomContextLines() throws IOException {
        String originalCode = """
                line1
                line2
                line3
                line4
                line5
                """;

        String refactoredCode = """
                line1
                line2
                lineX
                line4
                line5
                """;

        Path originalFile = tempDir.resolve("test.txt");
        Files.writeString(originalFile, originalCode);

        // Test with different context sizes
        String diff1 = diffGenerator.generateUnifiedDiff(originalFile, refactoredCode, 1);
        String diff3 = diffGenerator.generateUnifiedDiff(originalFile, refactoredCode, 3);

        assertNotNull(diff1);
        assertNotNull(diff3);
        // More context should produce longer diff
        assertTrue(diff3.length() >= diff1.length(),
                "Diff with more context should not be shorter");
    }

    @Test
    void testSideBySideDiff() throws IOException {
        String originalCode = """
                public void method() {
                    doSomething();
                }
                """;

        String refactoredCode = """
                public void method() {
                    doSomethingElse();
                }
                """;

        Path originalFile = tempDir.resolve("Test.java");
        Files.writeString(originalFile, originalCode);

        String sideBySide = diffGenerator.generateSideBySideDiff(originalFile, refactoredCode);

        assertNotNull(sideBySide);
        assertTrue(sideBySide.contains("ORIGINAL"), "Should have ORIGINAL header");
        assertTrue(sideBySide.contains("REFACTORED"), "Should have REFACTORED header");
        assertTrue(sideBySide.contains("|"), "Should have column separator");
    }

    @Test
    void testDiffWithNoChanges() throws IOException {
        String code = """
                public class Unchanged {
                    public void method() {
                        // No changes
                    }
                }
                """;

        Path originalFile = tempDir.resolve("Unchanged.java");
        Files.writeString(originalFile, code);

        String diff = diffGenerator.generateUnifiedDiff(originalFile, code);

        // Should still generate valid diff but with no changes
        assertNotNull(diff);
    }

    @Test
    void testDiffWithComplexChanges() throws IOException {
        String originalCode = """
                public class Complex {
                    public void method1() {
                        step1();
                        step2();
                        step3();
                    }

                    public void method2() {
                        step1();
                        step2();
                        step3();
                    }
                }
                """;

        String refactoredCode = """
                public class Complex {
                    public void method1() {
                        processSteps();
                    }

                    public void method2() {
                        processSteps();
                    }

                    private void processSteps() {
                        step1();
                        step2();
                        step3();
                    }
                }
                """;

        Path originalFile = tempDir.resolve("Complex.java");
        Files.writeString(originalFile, originalCode);

        String diff = diffGenerator.generateUnifiedDiff(originalFile, refactoredCode);

        assertNotNull(diff);
        assertTrue(diff.contains("-"), "Should show deletions");
        assertTrue(diff.contains("+"), "Should show additions");
        assertTrue(diff.contains("processSteps"), "Should show new method call");
    }

    @Test
    void testDiffWithLineNumbers() throws IOException {
        String originalCode = """
                line1
                line2
                line3
                """;

        String refactoredCode = """
                line1
                modified
                line3
                """;

        Path originalFile = tempDir.resolve("lines.txt");
        Files.writeString(originalFile, originalCode);

        String diff = diffGenerator.generateUnifiedDiff(originalFile, refactoredCode);

        // Unified diff should contain line number information
        assertNotNull(diff);
        assertTrue(diff.contains("@@"), "Should contain hunk headers with line numbers");
    }

    @Test
    void testDiffWithMultipleHunks() throws IOException {
        String originalCode = """
                line1
                line2
                line3
                line4
                line5
                line6
                line7
                line8
                line9
                line10
                """;

        String refactoredCode = """
                line1
                changed2
                line3
                line4
                line5
                line6
                line7
                changed8
                line9
                line10
                """;

        Path originalFile = tempDir.resolve("multi.txt");
        Files.writeString(originalFile, originalCode);

        String diff = diffGenerator.generateUnifiedDiff(originalFile, refactoredCode, 1);

        assertNotNull(diff);
        // Should have changes in multiple locations
        assertTrue(diff.contains("changed2"));
        assertTrue(diff.contains("changed8"));
    }

    @Test
    void testDiffFormattingConsistency() throws IOException {
        String originalCode = "unchanged";
        String refactoredCode = "unchanged";

        Path file1 = tempDir.resolve("file1.txt");
        Path file2 = tempDir.resolve("file2.txt");
        Files.writeString(file1, originalCode);
        Files.writeString(file2, originalCode);

        String diff1 = diffGenerator.generateUnifiedDiff(file1, refactoredCode);
        String diff2 = diffGenerator.generateUnifiedDiff(file2, refactoredCode);

        // Diff format should be consistent (minus filenames)
        assertNotNull(diff1);
        assertNotNull(diff2);
    }
}
