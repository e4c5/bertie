package com.raditha.bertie.testbed.variablecapture;

import com.raditha.bertie.testbed.model.User;
import java.util.ArrayList;
import java.util.List;

/**
 * VARIABLE CAPTURE BUG: Extracting Code That Modifies Outer Scope Variables
 *
 * This class has duplicates that MODIFY variables declared OUTSIDE the duplicate code.
 * Bertie CANNOT extract this code safely - it would break the counter logic.
 *
 * BUG SYMPTOM:
 * If Bertie extracts code that modifies 'counter' or 'names' (declared in outer scope),
 * the extracted method won't have access to these variables. The counter will stay 0
 * and lists will remain empty, causing tests to fail.
 *
 * EXPECTED BEHAVIOR:
 * - Bertie should DETECT that 'counter' is modified (escape analysis)
 * - Bertie should BLOCK extraction (validation should fail)
 * - If extracted anyway, tests will FAIL (counter not incremented)
 */
public class ServiceWithCounterVariable {

    /**
     * Processes items and counts valid ones.
     * The counter variable is OUTSIDE the duplicate but MODIFIED inside.
     */
    public int processItemsAndCount(List<User> users) {
        int counter = 0;  // ← Declared OUTSIDE

        for (User user : users) {
            // === DUPLICATE CODE STARTS ===
            if (user.isActive()) {
                user.save();
                counter++;  // ← MODIFIES outer variable!
            }
            // === DUPLICATE CODE ENDS ===
        }

        return counter;
    }

    /**
     * Similar method with same duplicate pattern.
     */
    public int processMoreItemsAndCount(List<User> users) {
        int counter = 0;  // ← Declared OUTSIDE

        for (User user : users) {
            // === DUPLICATE CODE STARTS ===
            if (user.isActive()) {
                user.save();
                counter++;  // ← MODIFIES outer variable!
            }
            // === DUPLICATE CODE ENDS ===
        }

        return counter;
    }

    /**
     * Yet another method with same pattern.
     */
    public int processThirdSetAndCount(List<User> users) {
        int counter = 0;  // ← Declared OUTSIDE

        for (User user : users) {
            // === DUPLICATE CODE STARTS ===
            if (user.isActive()) {
                user.save();
                counter++;  // ← MODIFIES outer variable!
            }
            // === DUPLICATE CODE ENDS ===
        }

        return counter;
    }

    /**
     * More complex example: accumulating results in outer list.
     */
    public List<String> collectActiveUserNames(List<User> users) {
        List<String> names = new ArrayList<>();  // ← Outer list

        for (User user : users) {
            // === DUPLICATE CODE ===
            if (user.isActive()) {
                names.add(user.getName());  // ← MODIFIES outer list!
            }
            // === DUPLICATE CODE ===
        }

        return names;
    }

    /**
     * Similar duplicate with outer list.
     */
    public List<String> collectAnotherSet(List<User> users) {
        List<String> names = new ArrayList<>();  // ← Outer list

        for (User user : users) {
            // === DUPLICATE CODE ===
            if (user.isActive()) {
                names.add(user.getName());  // ← MODIFIES outer list!
            }
            // === DUPLICATE CODE ===
        }

        return names;
    }
}
