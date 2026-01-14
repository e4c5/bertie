package com.raditha.bertie.testbed.parameterizedtest;

import com.raditha.bertie.testbed.model.User;
import com.raditha.bertie.testbed.repository.Repository;

/**
 * Test class demonstrating Gap 7: Over-Parameterization in Parameterized Tests
 *
 * This class contains duplicate test code with:
 * - VARYING values: user names, ages (should be parameterized)
 * - CONSTANT values: HTTP status codes, timeouts (should NOT be parameterized)
 *
 * CRITICAL: After refactoring to @ParameterizedTest, constants must remain
 * constants!
 * Only the varying values should become parameters.
 */
public class TestsWithConstantsAndVariables {

    private final Repository<User> repository;

    public TestsWithConstantsAndVariables(Repository<User> repository) {
        this.repository = repository;
    }

    /**
     * Test user John - has varying name/age and constant status code.
     *
     * VARYING: "John", 25
     * CONSTANT: 200 (HTTP OK)
     */
    public int testUserJohn() {
        // Duplicate test logic starts here
        User user = new User();
        user.setName("John");
        user.setAge(25);
        user.setActive(true);

        User saved = repository.save(user);
        int status = 200; // HTTP OK - THIS IS CONSTANT across all tests!

        if (saved.getId() != null && saved.getName().equals("John")) {
            return status; // Return 200
        }
        // Duplicate test logic ends here
        return 500; // Error
    }

    /**
     * Test user Jane - has varying name/age and constant status code.
     *
     * VARYING: "Jane", 30
     * CONSTANT: 200 (HTTP OK) - SAME as John's test!
     */
    public int testUserJane() {
        // Duplicate test logic starts here
        User user = new User();
        user.setName("Jane");
        user.setAge(30);
        user.setActive(true);

        User saved = repository.save(user);
        int status = 200; // HTTP OK - THIS IS CONSTANT across all tests!

        if (saved.getId() != null && saved.getName().equals("Jane")) {
            return status; // Return 200
        }
        // Duplicate test logic ends here
        return 500; // Error
    }

    /**
     * Test user Bob - has varying name/age and constant status code.
     *
     * VARYING: "Bob", 35
     * CONSTANT: 200 (HTTP OK) - SAME as other tests!
     */
    public int testUserBob() {
        // Duplicate test logic starts here
        User user = new User();
        user.setName("Bob");
        user.setAge(35);
        user.setActive(true);

        User saved = repository.save(user);
        int status = 200; // HTTP OK - THIS IS CONSTANT across all tests!

        if (saved.getId() != null && saved.getName().equals("Bob")) {
            return status; // Return 200
        }
        // Duplicate test logic ends here
        return 500; // Error
    }

    /**
     * Test with timeout constant - has varying data and constant timeout.
     *
     * VARYING: "Alice", 28
     * CONSTANT: 5000 (5 second timeout)
     */
    public long testUserAliceWithTimeout() {
        // Duplicate test logic with timeout
        User user = new User();
        user.setName("Alice");
        user.setAge(28);
        user.setActive(true);

        long timeout = 5000; // 5 seconds - THIS IS CONSTANT!
        long startTime = System.currentTimeMillis();

        User saved = repository.save(user);
        long elapsed = System.currentTimeMillis() - startTime;

        if (saved.getId() != null && elapsed < timeout) {
            return timeout; // Return the timeout value
        }
        // Duplicate test logic ends here
        return 0; // Failed
    }

    /**
     * Test with timeout constant - has varying data and same constant timeout.
     *
     * VARYING: "Charlie", 40
     * CONSTANT: 5000 (5 second timeout) - SAME as Alice's test!
     */
    public long testUserCharlieWithTimeout() {
        // Duplicate test logic with timeout
        User user = new User();
        user.setName("Charlie");
        user.setAge(40);
        user.setActive(true);

        long timeout = 5000; // 5 seconds - THIS IS CONSTANT!
        long startTime = System.currentTimeMillis();

        User saved = repository.save(user);
        long elapsed = System.currentTimeMillis() - startTime;

        if (saved.getId() != null && elapsed < timeout) {
            return timeout; // Return the timeout value
        }
        // Duplicate test logic ends here
        return 0; // Failed
    }
}
