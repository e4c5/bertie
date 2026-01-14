package com.raditha.bertie.testbed.staticcontext;

/**
 * Service with duplicates spanning BOTH static and instance methods.
 *
 * Gap 3: Static Modifier Inference is Wrong for Mixed Contexts
 *
 * CRITICAL: If Bertie creates a helper method based only on primary sequence,
 * it will create either static or instance method incorrectly.
 *
 * This will cause COMPILATION ERRORS when:
 * - Instance method tries to call static helper
 * - Static method tries to call instance helper (accessing 'this')
 */
public class ServiceWithMixedStaticContext {

    private String instanceField = "Instance Data";

    /**
     * Instance method with duplicate code.
     * Can access instance fields.
     */
    public String instanceMethod1() {
        // Duplicate code - START
        String prefix = "Result: ";
        String suffix = "!";
        String combined = prefix + "value" + suffix;
        // Duplicate code - END

        return combined + " from " + instanceField;
    }

    /**
     * Another instance method with duplicate code.
     * Can access instance fields.
     */
    public String instanceMethod2() {
        // Duplicate code - START
        String prefix = "Result: ";
        String suffix = "!";
        String combined = prefix + "value" + suffix;
        // Duplicate code - END

        return combined + " from " + instanceField;
    }

    /**
     * Static method with THE SAME duplicate code.
     * CANNOT access instance fields or instance methods!
     */
    public static String staticMethod1() {
        // Duplicate code - START
        String prefix = "Result: ";
        String suffix = "!";
        String combined = prefix + "value" + suffix;
        // Duplicate code - END

        return combined + " from static";
    }

    /**
     * Another static method with duplicate code.
     * CANNOT access instance fields or instance methods!
     */
    public static String staticMethod2() {
        // Duplicate code - START
        String prefix = "Result: ";
        String suffix = "!";
        String combined = prefix + "value" + suffix;
        // Duplicate code - END

        return combined + " from static";
    }
}
