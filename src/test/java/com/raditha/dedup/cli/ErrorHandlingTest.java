package com.raditha.dedup.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for error handling and help generation.
 * **Validates: Requirements 4.1, 4.3, 3.1, 3.2, 4.2, 4.4**
 */
class ErrorHandlingTest {

    private ByteArrayOutputStream outContent;
    private ByteArrayOutputStream errContent;
    private PrintStream originalOut;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        outContent = new ByteArrayOutputStream();
        errContent = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outContent));
        System.setErr(new PrintStream(errContent));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    @Test
    void testPicocliErrorHandlingForInvalidOptions() {
        // Test that Picocli generates proper error messages for invalid options
        CommandLine cmd = new CommandLine(new BertieCLI());
        int exitCode = cmd.execute("--invalid-option");
        
        assertNotEquals(0, exitCode, "Invalid option should return non-zero exit code");
        
        String errorOutput = errContent.toString();
        assertTrue(errorOutput.contains("Unknown option") || errorOutput.contains("Unmatched argument"),
            "Error message should indicate unknown option");
    }

    @Test
    void testPicocliErrorHandlingForMissingValues() {
        // Test that Picocli shows error for missing required parameter values
        CommandLine cmd = new CommandLine(new BertieCLI());
        int exitCode = cmd.execute("--min-lines");
        
        assertNotEquals(0, exitCode, "Missing parameter value should return non-zero exit code");
        
        String errorOutput = errContent.toString();
        assertTrue(errorOutput.contains("Missing required parameter") || 
                   errorOutput.contains("Expected parameter"),
            "Error message should indicate missing parameter");
    }

    @Test
    void testPicocliErrorHandlingForInvalidTypes() {
        // Test that Picocli handles type conversion errors properly
        CommandLine cmd = new CommandLine(new BertieCLI());
        int exitCode = cmd.execute("--min-lines", "not-a-number");
        
        assertNotEquals(0, exitCode, "Invalid type should return non-zero exit code");
        
        String errorOutput = errContent.toString();
        assertTrue(errorOutput.contains("Invalid value") || 
                   errorOutput.contains("NumberFormatException") ||
                   errorOutput.contains("not-a-number"),
            "Error message should indicate type conversion error");
    }

    @Test
    void testPicocliErrorHandlingForInvalidEnumValues() {
        // Test that custom converters provide clear error messages
        CommandLine cmd = new CommandLine(new BertieCLI());
        int exitCode = cmd.execute("refactor", "--mode", "invalid-mode");
        
        assertNotEquals(0, exitCode, "Invalid enum value should return non-zero exit code");
        
        String errorOutput = errContent.toString();
        assertTrue(errorOutput.contains("Invalid refactor mode") ||
                   errorOutput.contains("Must be: interactive, batch, or dry-run"),
            "Error message should indicate valid enum values");
    }

    @Test
    void testHelpGeneration() {
        // Test that --help generates proper Picocli help
        CommandLine cmd = new CommandLine(new BertieCLI());
        int exitCode = cmd.execute("--help");
        
        assertEquals(0, exitCode, "Help should return exit code 0");
        
        String output = outContent.toString();
        assertTrue(output.contains("Usage:"), "Help should contain usage section");
        assertTrue(output.contains("bertie"), "Help should contain program name");
        assertTrue(output.contains("Duplicate Code Detector"), "Help should contain description");
        assertTrue(output.contains("--min-lines"), "Help should contain option descriptions");
        assertTrue(output.contains("--threshold"), "Help should contain option descriptions");
        assertTrue(output.contains("--mode"), "Help should contain option descriptions");
        assertTrue(output.contains("--verify"), "Help should contain option descriptions");
        assertTrue(output.contains("--help"), "Help should contain help option");
        assertTrue(output.contains("--version"), "Help should contain version option");
    }

    @Test
    void testVersionOutput() {
        // Test that --version displays correct version information
        CommandLine cmd = new CommandLine(new BertieCLI());
        int exitCode = cmd.execute("--version");
        
        assertEquals(0, exitCode, "Version should return exit code 0");
        
        String output = outContent.toString();
        assertTrue(output.contains("Bertie v1.0.0"), "Version should contain expected format");
    }

    @Test
    void testShortHelpOption() {
        // Test that -h works the same as --help
        CommandLine cmd = new CommandLine(new BertieCLI());
        int exitCode = cmd.execute("-h");
        
        assertEquals(0, exitCode, "Short help option should return exit code 0");
        
        String output = outContent.toString();
        assertTrue(output.contains("Usage:"), "Short help should contain usage section");
    }

    @Test
    void testShortVersionOption() {
        // Test that -V works the same as --version
        CommandLine cmd = new CommandLine(new BertieCLI());
        int exitCode = cmd.execute("-V");
        
        assertEquals(0, exitCode, "Short version option should return exit code 0");
        
        String output = outContent.toString();
        assertTrue(output.contains("Bertie v1.0.0"), "Short version should contain expected format");
    }

    @Test
    void testExitCodeRanges() {
        // Test that exit codes are in valid ranges
        String[][] testCases = {
            {"--help"},           // Should be 0
            {"--version"},        // Should be 0
            {"--invalid-option"}, // Should be non-zero
            {"--min-lines", "invalid"} // Should be non-zero
        };
        
        for (String[] args : testCases) {
            CommandLine cmd = new CommandLine(new BertieCLI());
            int exitCode = cmd.execute(args);
            
            assertTrue(exitCode >= 0 && exitCode <= 255, 
                "Exit code should be in valid range 0-255 for args: " + String.join(" ", args));
        }
    }

    @Test
    void testErrorMessageQuality() {
        // Test that error messages are helpful and informative
        CommandLine cmd = new CommandLine(new BertieCLI());
        int exitCode = cmd.execute("--threshold", "150"); // Invalid range
        
        assertNotEquals(0, exitCode, "Invalid threshold should return non-zero exit code");
        
        // The error should be caught and handled appropriately
        // Even if the value is out of expected range, Picocli should handle it gracefully
    }

    @Test
    void testMultipleErrorsHandling() {
        // Test handling of multiple errors in single command
        CommandLine cmd = new CommandLine(new BertieCLI());
        int exitCode = cmd.execute("--invalid-option", "--min-lines", "invalid", "--unknown-flag");
        
        assertNotEquals(0, exitCode, "Multiple errors should return non-zero exit code");
        
        // Should handle the first error encountered
        String errorOutput = errContent.toString();
        assertFalse(errorOutput.isEmpty(), "Should produce some error output");
    }
}
