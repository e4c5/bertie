package com.raditha.dedup.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BertieCLITest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        Settings.loadConfigMap();
        Settings.setProperty(Settings.BASE_PATH, tempDir.toString());
    }

    @Test
    void testFindSourceFile_Main() throws Exception {
        // Create src/main/java/com/example/MainClass.java
        Path mainFile = tempDir.resolve("src/main/java/com/example/MainClass.java");
        Files.createDirectories(mainFile.getParent());
        Files.createFile(mainFile);

        Path result = invokeFindSourceFile("com.example.MainClass");
        assertNotNull(result);
        assertEquals(mainFile.toAbsolutePath(), result.toAbsolutePath());
    }

    @Test
    void testFindSourceFile_Test() throws Exception {
        // Create src/test/java/com/example/TestClass.java
        Path testFile = tempDir.resolve("src/test/java/com/example/TestClass.java");
        Files.createDirectories(testFile.getParent());
        Files.createFile(testFile);

        Path result = invokeFindSourceFile("com.example.TestClass");
        assertNotNull(result);
        assertEquals(testFile.toAbsolutePath(), result.toAbsolutePath());
    }

    @Test
    void testFindSourceFile_InnerClass() throws Exception {
        // Create src/main/java/com/example/Outer.java
        Path outerFile = tempDir.resolve("src/main/java/com/example/Outer.java");
        Files.createDirectories(outerFile.getParent());
        Files.createFile(outerFile);

        // Test finding Outer.Inner
        Path result = invokeFindSourceFile("com.example.Outer.Inner");
        assertNotNull(result);
        assertEquals(outerFile.toAbsolutePath(), result.toAbsolutePath());

        // Test finding Outer.Inner.Deep
        result = invokeFindSourceFile("com.example.Outer.Inner.Deep");
        assertNotNull(result);
        assertEquals(outerFile.toAbsolutePath(), result.toAbsolutePath());
    }

    @Test
    void testFindSourceFile_NotFound() throws Exception {
        Path result = invokeFindSourceFile("com.example.NonExistent");
        assertNull(result);
    }

    // Helper to invoke private static method
    private Path invokeFindSourceFile(String className) throws Exception {
        Method method = BertieCLI.class.getDeclaredMethod("findSourceFile", String.class,
                com.github.javaparser.ast.CompilationUnit.class);
        method.setAccessible(true);
        return (Path) method.invoke(null, className, null);
    }
}
