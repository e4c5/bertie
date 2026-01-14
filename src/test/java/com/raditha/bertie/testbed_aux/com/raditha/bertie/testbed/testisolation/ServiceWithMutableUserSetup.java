package com.raditha.bertie.testbed.testisolation;

import com.raditha.bertie.testbed.model.User;
import java.time.LocalDateTime;

/**
 * Service with duplicate setup code that uses MUTABLE objects.
 *
 * Gap 6: Field Promotion in @BeforeEach Creates Test Isolation Issues
 *
 * CRITICAL: If Bertie promotes the User object to an instance field,
 * it will create SHARED STATE between tests, breaking test isolation.
 *
 * Expected behavior: Each test should have its own independent User instance.
 * Broken behavior: Tests share the same User instance, causing flaky tests.
 */
public class ServiceWithMutableUserSetup {

    /**
     * Process Alice user with specific configuration.
     */
    public String processAlice() {
        // Duplicate setup code - START (8 lines)
        User user = new User();
        user.setName("Alice");
        user.setEmail("alice@example.com");
        user.setAge(25);
        user.setActive(true);
        user.setRole("user");
        user.setLastLogin(LocalDateTime.now());
        System.out.println("User initialized: " + user.getName());
        // Duplicate setup code - END

        // Business logic
        user.incrementLoginCount();
        return user.getName() + " logged in " + user.getLoginCount() + " times";
    }

    /**
     * Process Bob user with specific configuration.
     */
    public String processBob() {
        // Duplicate setup code - START (8 lines)
        User user = new User();
        user.setName("Bob");
        user.setEmail("bob@example.com");
        user.setAge(30);
        user.setActive(true);
        user.setRole("admin");
        user.setLastLogin(LocalDateTime.now());
        System.out.println("User initialized: " + user.getName());
        // Duplicate setup code - END

        // Business logic
        user.incrementLoginCount();
        user.incrementLoginCount();
        return user.getName() + " logged in " + user.getLoginCount() + " times";
    }

    /**
     * Process Charlie user with specific configuration.
     */
    public String processCharlie() {
        // Duplicate setup code - START (8 lines)
        User user = new User();
        user.setName("Charlie");
        user.setEmail("charlie@example.com");
        user.setAge(35);
        user.setActive(true);
        user.setRole("moderator");
        user.setLastLogin(LocalDateTime.now());
        System.out.println("User initialized: " + user.getName());
        // Duplicate setup code - END

        // Business logic
        user.incrementLoginCount();
        user.incrementLoginCount();
        user.incrementLoginCount();
        return user.getName() + " logged in " + user.getLoginCount() + " times";
    }

    /**
     * Process David user and modify state significantly.
     * If user is shared, this will affect other operations!
     */
    public boolean processWithHeavyModification() {
        // Duplicate setup code - START (8 lines)
        User user = new User();
        user.setName("David");
        user.setEmail("david@example.com");
        user.setAge(40);
        user.setActive(true);
        user.setRole("guest");
        user.setLastLogin(LocalDateTime.now());
        System.out.println("User initialized: " + user.getName());
        // Duplicate setup code - END

        // Heavy state modification
        user.setActive(false);
        user.setEmail("changed@example.com");
        for (int i = 0; i < 10; i++) {
            user.incrementLoginCount();
        }

        return !user.isActive() && user.getLoginCount() == 10;
    }
}
