package com.raditha.bertie.testbed.returnvalue;

import com.raditha.bertie.testbed.model.User;
import com.raditha.bertie.testbed.repository.Repository;

/**
 * Test SIMPLE RETURN VALUE scenario.
 *
 * Duplicate code creates a variable and uses it AFTER the duplicate.
 * The extracted method MUST return that variable.
 */
public class ServiceWithSimpleReturn {

    private final Repository<User> repository;

    public ServiceWithSimpleReturn(Repository<User> repository) {
        this.repository = repository;
    }

    /**
     * DUPLICATE 1: Creates user, uses it after
     */
    public String getUserName1(String userId) {
        User user = repository.findById(userId);
        user.setActive(true);
        user.save();

        // Uses 'user' here - so extracted method MUST return User
        return user.getName();
    }

    /**
     * DUPLICATE 2: Same pattern
     */
    public String getUserEmail2(String userId) {
        User user = repository.findById(userId);
        user.setActive(true);
        user.save();

        // Uses 'user' here - so extracted method MUST return User
        return user.getEmail();
    }

    /**
     * DUPLICATE 3: Same pattern
     */
    public boolean isUserActive3(String userId) {
        User user = repository.findById(userId);
        user.setActive(true);
        user.save();

        // Uses 'user' here - so extracted method MUST return User
        return user.isActive();
    }
}
