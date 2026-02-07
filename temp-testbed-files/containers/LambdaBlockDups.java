package com.raditha.bertie.testbed.containers;

import com.raditha.bertie.testbed.model.User;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Test class for detecting duplicate code in block-bodied lambda expressions.
 * Contains intentional duplicates that should be detected and refactored.
 */
public class LambdaBlockDups {
    
    private List<User> users;
    
    public LambdaBlockDups(List<User> users) {
        this.users = users;
    }
    
    public void processActiveUsers() {
        // Lambda with duplicate validation logic
        Consumer<User> processor = (user) -> {
            // Duplicate Block 1
            if (user == null) {
                throw new IllegalArgumentException("User cannot be null");
            }
            user.setStatus("PROCESSING");
            user.setLastModified(System.currentTimeMillis());
            user.validate();
        };
        
        users.stream()
            .filter(u -> "ACTIVE".equals(u.getStatus()))
            .forEach(processor);
    }
    
    public void processPendingUsers() {
        // Another lambda with similar validation
        Consumer<User> processor = (user) -> {
            // Duplicate Block 2 (similar to Block 1)
            if (user == null) {
                throw new IllegalArgumentException("User cannot be null");
            }
            user.setStatus("REVIEWED");
            user.setLastModified(System.currentTimeMillis());
            user.validate();
        };
        
        users.stream()
            .filter(u -> "PENDING".equals(u.getStatus()))
            .forEach(processor);
    }
    
    public List<User> filterByPredicate() {
        // Lambda with duplicate filtering logic
        Predicate<User> isValid = (user) -> {
            // Duplicate Block 3
            if (user.getName() == null || user.getName().isEmpty()) {
                return false;
            }
            if (user.getEmail() == null || user.getEmail().isEmpty()) {
                return false;
            }
            return user.getStatus() != null;
        };
        
        return users.stream().filter(isValid).toList();
    }
    
    public List<User> filterByAlternate() {
        // Another lambda with similar filtering
        Predicate<User> isComplete = (user) -> {
            // Duplicate Block 4 (similar to Block 3)
            if (user.getName() == null || user.getName().isEmpty()) {
                return false;
            }
            if (user.getEmail() == null || user.getEmail().isEmpty()) {
                return false;
            }
            return user.getTag() != null;
        };
        
        return users.stream().filter(isComplete).toList();
    }
}
