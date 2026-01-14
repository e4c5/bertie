package com.raditha.bertie.testbed.returnvalue;

import com.raditha.bertie.testbed.model.User;
import com.raditha.bertie.testbed.repository.Repository;

/**
 * Test NO RETURN needed scenario.
 *
 * Duplicate code has variables but NONE are used after.
 * The extracted method should return VOID.
 */
public class ServiceWithNoReturn {

    private final Repository<User> repository;

    public ServiceWithNoReturn(Repository<User> repository) {
        this.repository = repository;
    }

    /**
     * DUPLICATE 1: Creates user, but doesn't use it after
     */
    public void processUser1(String userId) {
        User user = repository.findById(userId);
        user.setActive(true);
        user.save();

        // NO USAGE of 'user' after this point
        // Extracted method should return VOID
    }

    /**
     * DUPLICATE 2: Same pattern - no variable usage after
     */
    public void processUser2(String userId) {
        User user = repository.findById(userId);
        user.setActive(true);
        user.save();

        // NO USAGE of 'user' after this point
        // Extracted method should return VOID
    }

    /**
     * DUPLICATE 3: Same pattern
     */
    public void processUser3(String userId) {
        User user = repository.findById(userId);
        user.setActive(true);
        user.save();

        // NO USAGE of 'user' after this point
    }
}
