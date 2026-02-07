package com.raditha.bertie.testbed.containers;

import com.raditha.bertie.testbed.model.User;
import java.util.List;
import java.util.function.Function;

/**
 * Test class for detecting duplicate code across method and nested lambda scopes.
 * Tests cross-scope duplicate detection and nested scope resolution.
 */
public class NestedLambdaDups {
    
    public List<String> processUsers(List<User> users) {
        // Duplicate Block 1 - In method scope
        if (users == null || users.isEmpty()) {
            throw new IllegalArgumentException("Users list cannot be null or empty");
        }
        
        // Lambda that captures from enclosing method
        Function<User, String> formatter = (user) -> {
            // Duplicate Block 2 - In lambda scope (similar to Block 1)
            if (user.getName() == null || user.getName().isEmpty()) {
                throw new IllegalArgumentException("Users list cannot be null or empty");
            }
            return user.getName() + " <" + user.getEmail() + ">";
        };
        
        return users.stream().map(formatter).toList();
    }
    
    public List<String> transformUsers(List<User> users) {
        // Duplicate Block 3 - In method scope (similar to Block 1)
        if (users == null || users.isEmpty()) {
            throw new IllegalArgumentException("Users list cannot be null or empty");
        }
        
        // Nested lambda with similar logic
        Function<User, String> transformer = (user) -> {
            // Duplicate Block 4 - In lambda scope
            if (user.getEmail() == null || user.getEmail().isEmpty()) {
                throw new IllegalArgumentException("Users list cannot be null or empty");
            }
            return user.getEmail() + " - " + user.getStatus();
        };
        
        return users.stream().map(transformer).toList();
    }
    
    public void updateUsers(List<User> users) {
        // Duplicate Block 5 - In method scope
        if (users == null || users.isEmpty()) {
            throw new IllegalArgumentException("Users list cannot be null or empty");
        }
        
        users.forEach(user -> {
            // Duplicate Block 6 - In lambda scope
            if (user.getTag() == null || user.getTag().isEmpty()) {
                throw new IllegalArgumentException("Users list cannot be null or empty");
            }
            user.setLastModified(System.currentTimeMillis());
        });
    }
    
    public List<User> filterAndMap(List<User> users) {
        // Duplicate Block 7 - In method scope
        if (users == null || users.isEmpty()) {
            throw new IllegalArgumentException("Users list cannot be null or empty");
        }
        
        return users.stream()
            .filter(user -> {
                // Duplicate Block 8 - In nested lambda
                if (user.getStatus() == null || user.getStatus().isEmpty()) {
                    throw new IllegalArgumentException("Users list cannot be null or empty");
                }
                return "ACTIVE".equals(user.getStatus());
            })
            .toList();
    }
}
