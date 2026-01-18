package com.raditha.dedup.refactoring;

import com.raditha.dedup.cli.VerifyMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.parser.MavenHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefactoringVerifierTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        Path configFile = tempDir.resolve("generator.yml");
        Files.writeString(configFile, "duplication_detector:\n  java_version: 21");
        Settings.loadConfigMap(configFile.toFile());
    }

    @Test
    void testGetSourcepath() {
        RefactoringVerifier verifier = new RefactoringVerifier(tempDir);
        String sourcepath = verifier.getSourcepath();
        
        String expectedMain = tempDir.resolve("src/main/java").toString();
        String expectedTest = tempDir.resolve("src/test/java").toString();
        
        assertTrue(sourcepath.contains(expectedMain));
        assertTrue(sourcepath.contains(expectedTest));
    }

    @Test
    void testGetClasspath() {
        try (MockedStatic<MavenHelper> mockedMaven = mockStatic(MavenHelper.class)) {
            mockedMaven.when(MavenHelper::getJarPaths).thenReturn(new String[]{"lib1.jar", "lib2.jar"});
            
            RefactoringVerifier verifier = new RefactoringVerifier(tempDir);
            String classpath = verifier.getClasspath();
            
            assertTrue(classpath.contains("lib1.jar"));
            assertTrue(classpath.contains("lib2.jar"));
            assertTrue(classpath.contains(tempDir.resolve("target/classes").toString()));
            assertTrue(classpath.contains(tempDir.resolve("target/test-classes").toString()));
        }
    }

    @Test
    void testInvalidateCache() {
        RefactoringVerifier verifier = new RefactoringVerifier(tempDir);
        
        String cp1 = verifier.getClasspath();
        String sp1 = verifier.getSourcepath();
        
        verifier.invalidateCache();
        
        // This is a bit hard to test without reflection or observer, 
        // but we can at least check it doesn't crash and returns valid paths again.
        assertNotNull(verifier.getClasspath());
        assertNotNull(verifier.getSourcepath());
    }

    @Test
    void testCreateBackupInvalidatesCacheForNewFiles() throws IOException {
        RefactoringVerifier verifier = spy(new RefactoringVerifier(tempDir));
        Path newFile = tempDir.resolve("NewClass.java");
        
        verifier.createBackup(newFile);
        
        verify(verifier).invalidateCache();
    }
    
    @Test
    void testCreateBackupDoesNotInvalidateCacheForExistingFiles() throws IOException {
        RefactoringVerifier verifier = spy(new RefactoringVerifier(tempDir));
        Path existingFile = tempDir.resolve("ExistingClass.java");
        Files.writeString(existingFile, "content");
        
        verifier.createBackup(existingFile);
        
        verify(verifier, never()).invalidateCache();
    }

    @Test
    void testRunFastCompileWithDependencies() throws IOException, InterruptedException {
        // Create a project structure
        Path srcMain = tempDir.resolve("src/main/java");
        Files.createDirectories(srcMain);
        
        // Base class
        Path baseClassPath = srcMain.resolve("BaseClass.java");
        Files.writeString(baseClassPath, "public class BaseClass { public void hello() {} }");
        
        // Child class that depends on BaseClass (not modified yet)
        Path childClassPath = srcMain.resolve("ChildClass.java");
        Files.writeString(childClassPath, "public class ChildClass extends BaseClass { }");
        
        RefactoringVerifier verifier = new RefactoringVerifier(tempDir, VerifyMode.FAST_COMPILE);
        
        // Simulate a refactoring that modifies ChildClass but NOT BaseClass
        verifier.createBackup(childClassPath);
        Files.writeString(childClassPath, "public class ChildClass extends BaseClass { @Override public void hello() { super.hello(); } }");
        
        // runFastCompile should succeed because it finds BaseClass on the sourcepath
        RefactoringVerifier.VerificationResult result = verifier.verify();
        
        assertTrue(result.isSuccess(), "Compilation should succeed with sourcepath resolution. Errors: " + result.errors());
    }

    @Test
    void testRollback() throws IOException {
        Path file1 = tempDir.resolve("File1.java");
        Files.writeString(file1, "original1");
        Path file2 = tempDir.resolve("File2.java");
        Files.writeString(file2, "original2");
        Path newFile = tempDir.resolve("NewFile.java");
        
        RefactoringVerifier verifier = new RefactoringVerifier(tempDir);
        
        verifier.createBackup(file1);
        verifier.createBackup(file2);
        verifier.createBackup(newFile);
        
        Files.writeString(file1, "modified1");
        Files.writeString(file2, "modified2");
        Files.writeString(newFile, "created");
        
        verifier.rollback();
        
        assertEquals("original1", Files.readString(file1));
        assertEquals("original2", Files.readString(file2));
        assertFalse(Files.exists(newFile), "NewFile should have been deleted on rollback");
    }

    @Test
    void testClearBackups() throws IOException {
        Path file1 = tempDir.resolve("File1.java");
        Files.writeString(file1, "original1");
        
        RefactoringVerifier verifier = new RefactoringVerifier(tempDir);
        verifier.createBackup(file1);
        
        verifier.clearBackups();
        
        // After clear, rollback should do nothing
        Files.writeString(file1, "modified1");
        verifier.rollback();
        
        assertEquals("modified1", Files.readString(file1), "Rollback should do nothing after clearBackups");
    }

    @Test
    void testExtractErrors() {
        RefactoringVerifier verifier = new RefactoringVerifier(tempDir);
        String output = "[INFO] Scanning for projects...\n" +
                        "[ERROR] /path/to/File.java:[10,20] symbol not found\n" +
                        "FAILED: some test\n" +
                        "[INFO] Success";
        
        List<String> errors = verifier.extractErrors(output);
        
        assertEquals(2, errors.size());
        assertTrue(errors.get(0).contains("[ERROR]"));
        assertTrue(errors.get(1).contains("FAILED"));
    }

    @Test
    void testExtractErrorsUnknown() {
        RefactoringVerifier verifier = new RefactoringVerifier(tempDir);
        List<String> errors = verifier.extractErrors("Some random output without markers");
        
        assertEquals(1, errors.size());
        assertEquals("Unknown error occurred", errors.get(0));
    }

    @Test
    void testReadOutput() throws IOException {
        RefactoringVerifier verifier = new RefactoringVerifier(tempDir);
        Process mockProcess = mock(Process.class);
        String expectedOutput = "line1\nline2\n";
        InputStream inputStream = new ByteArrayInputStream(expectedOutput.getBytes());
        
        when(mockProcess.getInputStream()).thenReturn(inputStream);
        
        String output = verifier.readOutput(mockProcess);
        assertEquals(expectedOutput, output);
    }

    @ParameterizedTest
    @EnumSource(VerifyMode.class)
    void testVerifyRouting(VerifyMode mode) throws IOException, InterruptedException {
        // We use a spy to verify which internal method is called
        RefactoringVerifier verifier = spy(new RefactoringVerifier(tempDir, mode));
        
        // Mock the internal calls to avoid actually running Maven or Compiler
        // We only care about the routing in verify()
        if (mode == VerifyMode.FAST_COMPILE) {
            doReturn(new RefactoringVerifier.CompilationResult(true, List.of(), "")).when(verifier).runFastCompile();
        } else if (mode == VerifyMode.COMPILE || mode == VerifyMode.TEST) {
            doReturn(new RefactoringVerifier.CompilationResult(true, List.of(), "")).when(verifier).runMavenCompile();
            if (mode == VerifyMode.TEST) {
                doReturn(new RefactoringVerifier.TestResult(true, List.of(), "")).when(verifier).runMavenTest();
            }
        }
        
        RefactoringVerifier.VerificationResult result = verifier.verify();
        
        if (mode == VerifyMode.NONE) {
            assertTrue(result.isSuccess());
            assertEquals("Verification skipped", result.message());
            verify(verifier, never()).runFastCompile();
            verify(verifier, never()).runMavenCompile();
        } else if (mode == VerifyMode.FAST_COMPILE) {
            verify(verifier).runFastCompile();
            verify(verifier, never()).runMavenCompile();
        } else {
            verify(verifier).runMavenCompile();
            verify(verifier, never()).runFastCompile();
            if (mode == VerifyMode.TEST) {
                verify(verifier).runMavenTest();
            }
        }
    }

    @Test
    void testRunFastCompileFailure() throws IOException, InterruptedException {
        Path srcMain = tempDir.resolve("src/main/java");
        Files.createDirectories(srcMain);
        
        Path invalidFile = srcMain.resolve("Invalid.java");
        Files.writeString(invalidFile, "public class Invalid { !! invalid code !! }");
        
        RefactoringVerifier verifier = new RefactoringVerifier(tempDir, VerifyMode.FAST_COMPILE);
        verifier.createBackup(invalidFile);
        
        RefactoringVerifier.VerificationResult result = verifier.verify();
        
        assertFalse(result.isSuccess(), "Compilation should have failed");
        assertFalse(result.errors().isEmpty(), "Errors should not be empty");
        
        // Sometimes the compiler might not include the full path in the diagnostic object's toString
        // but it should include the error message.
        boolean foundError = result.errors().stream().anyMatch(e -> e.contains("illegal") || e.contains("Invalid.java") || e.contains("error"));
        assertTrue(foundError, "Should have found an error message in: " + result.errors());
    }

    @Test
    void testDeleteDirectoryRecursively() throws IOException {
        Path subdir = tempDir.resolve("subdir");
        Files.createDirectories(subdir);
        Files.writeString(subdir.resolve("file.txt"), "hello");
        
        RefactoringVerifier verifier = new RefactoringVerifier(tempDir);
        verifier.deleteDirectoryRecursively(subdir);
        
        assertFalse(Files.exists(subdir));
    }
}
