package com.raditha.bertie.testbed.containers;

import com.raditha.bertie.testbed.model.User;
import com.raditha.bertie.testbed.model.Database;

/**
 * Test class for detecting duplicate code in instance initializer blocks.
 * Contains intentional duplicates that should be detected and refactored.
 */
public class InstanceInitializerDups {
    
    private User user;
    private Database database;
    private String sessionId;
    
    // Instance initializer with duplicate setup logic
    {
        user = new User();
        
        // Duplicate Block 1
        user.setName("DefaultUser");
        user.setEmail("user@example.com");
        user.setStatus("PENDING");
        user.validate();
        user.save();
    }
    
    // Another instance initializer with similar setup
    {
        database = new Database();
        
        // Duplicate Block 2 (similar to Block 1)
        database.setHost("localhost");
        database.setPort(5432);
        database.setDatabase("testdb");
        database.connect("jdbc:postgresql://localhost:5432/testdb");
        database.enablePooling();
    }
    
    // Third instance initializer
    {
        sessionId = java.util.UUID.randomUUID().toString();
        
        // Duplicate Block 3 (similar pattern)
        sessionId = sessionId.trim();
        sessionId = sessionId.toUpperCase();
        sessionId = sessionId.substring(0, 8);
    }
    
    public InstanceInitializerDups() {
        // Constructor can be empty, initialization happens in instance initializers
    }
    
    public InstanceInitializerDups(String name) {
        this.user.setName(name);
    }
    
    public User getUser() {
        return user;
    }
    
    public Database getDatabase() {
        return database;
    }
    
    public String getSessionId() {
        return sessionId;
    }
}
