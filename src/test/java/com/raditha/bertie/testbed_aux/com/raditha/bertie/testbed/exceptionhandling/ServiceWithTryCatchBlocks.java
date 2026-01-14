package com.raditha.bertie.testbed.exceptionhandling;

import java.io.IOException;

/**
 * Service demonstrating Gap 4: Exception Handling Propagation Issues
 *
 * This class has duplicate code with different exception handling patterns.
 * Bertie should NOT copy exception declarations from containing methods,
 * but should analyze what the extracted code actually throws.
 *
 * CRITICAL: After refactoring, exception behavior must remain unchanged!
 */
public class ServiceWithTryCatchBlocks {

    /**
     * Process data with exceptions HANDLED internally.
     * This method does NOT throw exceptions.
     *
     * If Bertie extracts this and adds "throws IOException",
     * it changes the method signature incorrectly!
     */
    public String processWithHandledExceptions(String data) {
        String result = null;
        try {
            // Duplicate code block starts here
            String normalized = data.trim().toLowerCase();
            if (normalized.isEmpty()) {
                throw new IOException("Empty data");
            }
            String processed = normalized.replace("_", "-");
            result = "Processed: " + processed;
            // Duplicate code block ends here
        } catch (IOException e) {
            result = "Error: " + e.getMessage();
        }
        return result;
    }

    /**
     * Process more data with exceptions HANDLED internally.
     * This method also does NOT throw exceptions.
     */
    public String processMoreWithHandledExceptions(String data) {
        String result = null;
        try {
            // Duplicate code block starts here (same as above)
            String normalized = data.trim().toLowerCase();
            if (normalized.isEmpty()) {
                throw new IOException("Empty data");
            }
            String processed = normalized.replace("_", "-");
            result = "Processed: " + processed;
            // Duplicate code block ends here
        } catch (IOException e) {
            result = "Error: " + e.getMessage();
        }
        return result;
    }

    /**
     * Validate data - throws checked exception.
     * This method DOES throw IOException.
     *
     * If Bertie extracts and forgets to add "throws IOException",
     * it will cause compilation error!
     */
    public boolean validateWithUncheckedExceptions(String data) throws IOException {
        // Duplicate code block starts here
        String cleaned = data.trim();
        if (cleaned.length() < 3) {
            throw new IOException("Data too short: " + cleaned);
        }
        if (cleaned.contains("invalid")) {
            throw new IOException("Invalid data: " + cleaned);
        }
        boolean isValid = cleaned.matches("[a-zA-Z0-9]+");
        // Duplicate code block ends here
        return isValid;
    }

    /**
     * Validate more data - also throws checked exception.
     * This method also throws IOException.
     */
    public boolean validateMoreWithUncheckedExceptions(String data) throws IOException {
        // Duplicate code block starts here (same as above)
        String cleaned = data.trim();
        if (cleaned.length() < 3) {
            throw new IOException("Data too short: " + cleaned);
        }
        if (cleaned.contains("invalid")) {
            throw new IOException("Invalid data: " + cleaned);
        }
        boolean isValid = cleaned.matches("[a-zA-Z0-9]+");
        // Duplicate code block ends here
        return isValid;
    }

    /**
     * Process with NO exceptions at all.
     * This is pure computation, no I/O.
     */
    public int calculateSum(int[] numbers) {
        // Duplicate code block starts here
        int sum = 0;
        for (int num : numbers) {
            if (num > 0) {
                sum += num;
            }
        }
        int doubled = sum * 2;
        // Duplicate code block ends here
        return doubled;
    }

    /**
     * Calculate more with NO exceptions.
     */
    public int calculateProduct(int[] numbers) {
        // Duplicate code block starts here (similar to above)
        int sum = 0;
        for (int num : numbers) {
            if (num > 0) {
                sum += num;
            }
        }
        int doubled = sum * 2;
        // Duplicate code block ends here
        return doubled * 10; // Different final step
    }
}
