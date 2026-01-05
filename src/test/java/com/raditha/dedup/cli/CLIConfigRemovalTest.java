package com.raditha.dedup.cli;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test to verify CLIConfig removal.
 * Tests that CLIConfig class no longer exists and parseArguments method is removed.
 * **Validates: Requirements 2.1, 6.1**
 */
class CLIConfigRemovalTest {

    @Test
    void testCLIConfigClassRemoved() {
        // Test that CLIConfig class no longer exists in the codebase
        assertThrows(ClassNotFoundException.class, () -> {
            Class.forName("com.raditha.dedup.cli.BertieCLI$CLIConfig");
        }, "CLIConfig inner class should be removed");
        
        // Also test the standalone class path
        assertThrows(ClassNotFoundException.class, () -> {
            Class.forName("com.raditha.dedup.cli.CLIConfig");
        }, "CLIConfig standalone class should not exist");
    }

    @Test
    void testParseArgumentsMethodRemoved() {
        // Test that parseArguments method is removed from BertieCLI
        Class<BertieCLI> clazz = BertieCLI.class;
        Method[] methods = clazz.getDeclaredMethods();
        
        for (Method method : methods) {
            assertNotEquals("parseArguments", method.getName(), 
                "parseArguments method should be removed from BertieCLI");
        }
    }

    @Test
    void testBertieCLIImplementsCallable() {
        // Test that BertieCLI implements Callable<Integer> interface
        assertTrue(java.util.concurrent.Callable.class.isAssignableFrom(BertieCLI.class),
            "BertieCLI should implement Callable<Integer>");
    }

    @Test
    void testBertieCLIHasCallMethod() {
        // Test that BertieCLI has the call() method from Callable interface
        assertDoesNotThrow(() -> {
            Method callMethod = BertieCLI.class.getMethod("call");
            assertEquals(Integer.class, callMethod.getReturnType(),
                "call() method should return Integer");
        }, "BertieCLI should have call() method");
    }

    @Test
    void testPicocliAnnotationsPresent() {
        // Test that BertieCLI class has Picocli annotations
        assertTrue(BertieCLI.class.isAnnotationPresent(picocli.CommandLine.Command.class),
            "BertieCLI should have @Command annotation");
    }
}