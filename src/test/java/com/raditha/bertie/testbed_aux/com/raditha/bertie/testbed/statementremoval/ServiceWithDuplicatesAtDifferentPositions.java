package com.raditha.bertie.testbed.statementremoval;

import com.raditha.bertie.testbed.model.User;
import java.time.LocalDateTime;

/**
 * Service with duplicate code at DIFFERENT POSITIONS within methods.
 *
 * Gap 9: Duplicate Removal Logic Doesn't Account for Statement Position Changes
 *
 * CRITICAL: If Bertie always removes from index 0, it will delete the WRONG statements.
 * This class has duplicates at:
 * - Start of method (index 0)
 * - Middle of method (index 3-5)
 * - End of method (last statements)
 */
public class ServiceWithDuplicatesAtDifferentPositions {

    /**
     * Process user with duplicate at START (index 0-7)
     */
    public String processDuplicateAtStart() {
        // DUPLICATE CODE - START (index 0) - 8 lines
        User user = new User();
        user.setName("Start User");
        user.setEmail("start@example.com");
        user.setAge(25);
        user.setActive(true);
        user.setRole("user");
        user.setLastLogin(LocalDateTime.now());
        System.out.println("Processing user: " + user.getName());
        // DUPLICATE CODE - END

        // Non-duplicate code
        user.setEmail("start@example.com");
        return user.getName();
    }

    /**
     * Process another user with duplicate at START (index 0-7)
     */
    public String processAnotherDuplicateAtStart() {
        // DUPLICATE CODE - START (index 0) - 8 lines
        User user = new User();
        user.setName("Start User 2");
        user.setEmail("start2@example.com");
        user.setAge(30);
        user.setActive(true);
        user.setRole("admin");
        user.setLastLogin(LocalDateTime.now());
        System.out.println("Processing user: " + user.getName());
        // DUPLICATE CODE - END

        // Non-duplicate code
        user.setEmail("start2@example.com");
        return user.getName();
    }

    /**
     * Process user with duplicate in MIDDLE (index 3-10)
     */
    public String processDuplicateInMiddle() {
        // Non-duplicate code at start
        String prefix = "Middle:";
        int count = 0;
        count++;

        // DUPLICATE CODE - START (index 3) - 8 lines
        User user = new User();
        user.setName("Middle User");
        user.setEmail("middle@example.com");
        user.setAge(28);
        user.setActive(true);
        user.setRole("moderator");
        user.setLastLogin(LocalDateTime.now());
        System.out.println("Processing user: " + user.getName());
        // DUPLICATE CODE - END

        // Non-duplicate code at end
        return prefix + user.getName();
    }

    /**
     * Process another user with duplicate in MIDDLE (index 3-10)
     */
    public String processAnotherDuplicateInMiddle() {
        // Non-duplicate code at start
        String prefix = "Middle2:";
        int count = 0;
        count += 2;

        // DUPLICATE CODE - START (index 3) - 8 lines
        User user = new User();
        user.setName("Middle User 2");
        user.setEmail("middle2@example.com");
        user.setAge(32);
        user.setActive(true);
        user.setRole("editor");
        user.setLastLogin(LocalDateTime.now());
        System.out.println("Processing user: " + user.getName());
        // DUPLICATE CODE - END

        // Non-duplicate code at end
        return prefix + user.getName();
    }

    /**
     * Process user with duplicate at END (index 4-11, last 8 statements)
     */
    public String processDuplicateAtEnd() {
        // Non-duplicate code at start
        String message = "Processing...";
        int counter = 0;
        counter++;
        System.out.println("Starting processing");

        // DUPLICATE CODE - START (index 4) - 8 lines
        User user = new User();
        user.setName("End User");
        user.setEmail("end@example.com");
        user.setAge(35);
        user.setActive(true);
        user.setRole("guest");
        user.setLastLogin(LocalDateTime.now());
        System.out.println("Processing user: " + user.getName());
        // DUPLICATE CODE - END (last statements)

        return message + ": " + user.getName();
    }

    /**
     * Process another user with duplicate at END (index 4-11, last 8 statements)
     */
    public String processAnotherDuplicateAtEnd() {
        // Non-duplicate code at start
        String message = "Processing again...";
        int counter = 0;
        counter += 2;
        System.out.println("Starting another processing");

        // DUPLICATE CODE - START (index 4) - 8 lines
        User user = new User();
        user.setName("End User 2");
        user.setEmail("end2@example.com");
        user.setAge(40);
        user.setActive(true);
        user.setRole("viewer");
        user.setLastLogin(LocalDateTime.now());
        System.out.println("Processing user: " + user.getName());
        // DUPLICATE CODE - END (last statements)

        return message + ": " + user.getName();
    }
}
