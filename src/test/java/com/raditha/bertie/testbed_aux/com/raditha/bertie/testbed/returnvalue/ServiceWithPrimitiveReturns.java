package com.raditha.bertie.testbed.returnvalue;

/**
 * Test PRIMITIVE RETURN TYPES scenario.
 *
 * Duplicates that should return primitive types (int, boolean, double, etc.)
 */
public class ServiceWithPrimitiveReturns {

    /**
     * DUPLICATE 1: Returns int
     */
    public int calculateTotal1(int a, int b) {
        int sum = a + b;
        int tax = sum * 10 / 100;
        int total = sum + tax;

        // Uses 'total' - extracted method should return int
        return total;
    }

    /**
     * DUPLICATE 2: Returns int
     */
    public int calculateTotal2(int x, int y) {
        int sum = x + y;
        int tax = sum * 10 / 100;
        int total = sum + tax;

        // Uses 'total' - extracted method should return int
        return total;
    }

    /**
     * DUPLICATE 3: Returns boolean
     */
    public boolean isValid1(String value) {
        boolean notNull = value != null;
        boolean notEmpty = !value.isEmpty();
        boolean isValid = notNull && notEmpty;

        // Uses 'isValid' - extracted method should return boolean
        return isValid;
    }

    /**
     * DUPLICATE 4: Returns boolean
     */
    public boolean isValid2(String input) {
        boolean notNull = input != null;
        boolean notEmpty = !input.isEmpty();
        boolean isValid = notNull && notEmpty;

        // Uses 'isValid' - extracted method should return boolean
        return isValid;
    }
}
