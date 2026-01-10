package com.raditha.dedup.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test for Maven dependency verification.
 * Validates that Picocli classes are available on the classpath.
 * **Validates: Requirements 1.1, 1.2**
 */
class MavenDependencyTest {

    @Test
    void testPicocliClassesAvailable() {
        // Test that core Picocli classes are available
        assertDoesNotThrow(() -> {
            Class.forName("picocli.CommandLine");
            Class.forName("picocli.CommandLine$Command");
            Class.forName("picocli.CommandLine$Option");
            Class.forName("picocli.CommandLine$ITypeConverter");
        }, "Picocli classes should be available on classpath");
    }

    @Test
    void testPicocliInstantiation() {
        // Test that we can instantiate Picocli CommandLine
        assertDoesNotThrow(() -> {
            BertieCLI cli = new BertieCLI();
            CommandLine commandLine = new CommandLine(cli);
            assertNotNull(commandLine);
        }, "Should be able to instantiate CommandLine with BertieCLI");
    }

    @Test
    void testPicocliVersionCompatibility() {
        // Test that Picocli version is compatible with Java 21
        assertDoesNotThrow(() -> {
            BertieCLI cli = new BertieCLI();
            CommandLine commandLine = new CommandLine(cli);
            
            // Test basic functionality that requires Java 21 compatibility
            String[] helpArgs = {"--help"};
            commandLine.parseArgs(helpArgs);
            
        }, "Picocli should be compatible with Java 21");
    }
}