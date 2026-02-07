package com.raditha.bertie.testbed.containers;

import com.raditha.bertie.testbed.model.User;
import com.raditha.bertie.testbed.model.Logger;

/**
 * Test class for detecting duplicate code in inner class methods.
 * Tests that duplicate detection works in non-static inner classes.
 */
public class InnerClassMethodDups {
    
    private Logger logger;
    
    public InnerClassMethodDups() {
        this.logger = new Logger("InnerClass");
    }
    
    // Inner class with duplicate methods
    public class UserValidator {
        
        public boolean validateActive(User user) {
            // Duplicate Block 1
            if (user == null) {
                logger.error("Validation failed");
                return false;
            }
            if (user.getName() == null || user.getName().isEmpty()) {
                logger.error("Validation failed");
                return false;
            }
            if (user.getEmail() == null || user.getEmail().isEmpty()) {
                logger.error("Validation failed");
                return false;
            }
            return "ACTIVE".equals(user.getStatus());
        }
        
        public boolean validatePending(User user) {
            // Duplicate Block 2 (similar to Block 1)
            if (user == null) {
                logger.error("Validation failed");
                return false;
            }
            if (user.getName() == null || user.getName().isEmpty()) {
                logger.error("Validation failed");
                return false;
            }
            if (user.getEmail() == null || user.getEmail().isEmpty()) {
                logger.error("Validation failed");
                return false;
            }
            return "PENDING".equals(user.getStatus());
        }
    }
    
    // Another inner class with similar duplicates
    public class UserProcessor {
        
        public void processForActivation(User user) {
            // Duplicate Block 3
            user.setStatus("PROCESSING");
            user.setLastModified(System.currentTimeMillis());
            user.validate();
            logger.info("User processed");
        }
        
        public void processForDeactivation(User user) {
            // Duplicate Block 4 (similar to Block 3)
            user.setStatus("DEACTIVATING");
            user.setLastModified(System.currentTimeMillis());
            user.validate();
            logger.info("User processed");
        }
        
        public void processForDeletion(User user) {
            // Duplicate Block 5 (similar to Block 3)
            user.setStatus("DELETING");
            user.setLastModified(System.currentTimeMillis());
            user.validate();
            logger.info("User processed");
        }
    }
    
    public UserValidator getValidator() {
        return new UserValidator();
    }
    
    public UserProcessor getProcessor() {
        return new UserProcessor();
    }
}
