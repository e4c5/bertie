package com.raditha.bertie.testbed.containers;

import com.raditha.bertie.testbed.model.User;
import com.raditha.bertie.testbed.model.Logger;
import java.util.Comparator;

/**
 * Test class for detecting duplicate code in anonymous class methods.
 * Phase 1: Detection only - refactoring of anonymous classes is complex
 * and deferred to a later phase.
 */
public class AnonymousClassDups {
    
    private Logger logger;
    
    public AnonymousClassDups() {
        this.logger = new Logger("AnonymousClass");
    }
    
    public Comparator<User> getNameComparator() {
        // Anonymous class with duplicate validation logic
        return new Comparator<User>() {
            @Override
            public int compare(User u1, User u2) {
                // Duplicate Block 1
                if (u1 == null || u2 == null) {
                    logger.error("Cannot compare null users");
                    return 0;
                }
                if (u1.getName() == null || u2.getName() == null) {
                    logger.error("Cannot compare null users");
                    return 0;
                }
                return u1.getName().compareTo(u2.getName());
            }
        };
    }
    
    public Comparator<User> getEmailComparator() {
        // Another anonymous class with similar validation
        return new Comparator<User>() {
            @Override
            public int compare(User u1, User u2) {
                // Duplicate Block 2 (similar to Block 1)
                if (u1 == null || u2 == null) {
                    logger.error("Cannot compare null users");
                    return 0;
                }
                if (u1.getEmail() == null || u2.getEmail() == null) {
                    logger.error("Cannot compare null users");
                    return 0;
                }
                return u1.getEmail().compareTo(u2.getEmail());
            }
        };
    }
    
    public Runnable getValidationTask(User user) {
        // Anonymous Runnable with duplicate validation
        return new Runnable() {
            @Override
            public void run() {
                // Duplicate Block 3
                if (user == null) {
                    logger.error("Validation task failed");
                    return;
                }
                if (user.getName() == null || user.getName().isEmpty()) {
                    logger.error("Validation task failed");
                    return;
                }
                user.validate();
                logger.info("Validation complete");
            }
        };
    }
    
    public Runnable getProcessingTask(User user) {
        // Another anonymous Runnable with similar logic
        return new Runnable() {
            @Override
            public void run() {
                // Duplicate Block 4 (similar to Block 3)
                if (user == null) {
                    logger.error("Validation task failed");
                    return;
                }
                if (user.getEmail() == null || user.getEmail().isEmpty()) {
                    logger.error("Validation task failed");
                    return;
                }
                user.setLastModified(System.currentTimeMillis());
                logger.info("Validation complete");
            }
        };
    }
}
