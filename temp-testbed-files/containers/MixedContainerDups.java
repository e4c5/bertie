package com.raditha.bertie.testbed.containers;

import com.raditha.bertie.testbed.model.User;
import com.raditha.bertie.testbed.model.Logger;
import java.util.List;
import java.util.function.Predicate;

/**
 * Test class for detecting duplicate code across different container types.
 * This is the most complex test case - duplicates appear in methods, lambdas,
 * static initializers, and instance initializers.
 */
public class MixedContainerDups {
    
    private static Logger staticLogger;
    private Logger instanceLogger;
    private List<User> users;
    
    // Static initializer with duplicate validation
    static {
        staticLogger = new Logger("Static");
        // Duplicate Block 1
        if (staticLogger == null) {
            throw new IllegalStateException("Logger initialization failed");
        }
        staticLogger.setLevel("INFO");
        staticLogger.enable();
    }
    
    // Instance initializer with similar validation
    {
        instanceLogger = new Logger("Instance");
        // Duplicate Block 2 (similar to Block 1)
        if (instanceLogger == null) {
            throw new IllegalStateException("Logger initialization failed");
        }
        instanceLogger.setLevel("DEBUG");
        instanceLogger.enable();
    }
    
    public MixedContainerDups(List<User> users) {
        this.users = users;
        // Duplicate Block 3 (in constructor, similar to initializers)
        if (users == null) {
            throw new IllegalStateException("Logger initialization failed");
        }
        users.forEach(u -> u.validate());
    }
    
    public void processUsers() {
        // Method with duplicate validation
        // Duplicate Block 4 (in method)
        if (users == null || users.isEmpty()) {
            staticLogger.error("No users to process");
            return;
        }
        
        // Lambda with similar validation
        Predicate<User> validator = (user) -> {
            // Duplicate Block 5 (in lambda)
            if (user == null || user.getName() == null) {
                staticLogger.error("No users to process");
                return false;
            }
            return user.getStatus() != null;
        };
        
        users.stream().filter(validator).forEach(user -> {
            // Duplicate Block 6 (in nested lambda)
            user.setStatus("PROCESSED");
            user.setLastModified(System.currentTimeMillis());
            user.validate();
        });
    }
    
    public void updateUsers() {
        // Another method with similar validation
        // Duplicate Block 7 (in method)
        if (users == null || users.isEmpty()) {
            staticLogger.error("No users to process");
            return;
        }
        
        users.forEach(user -> {
            // Duplicate Block 8 (in lambda)
            user.setStatus("UPDATED");
            user.setLastModified(System.currentTimeMillis());
            user.validate();
        });
    }
}
