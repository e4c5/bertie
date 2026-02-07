package com.raditha.bertie.testbed.model;

/**
 * User model class with methods needed for container duplicate tests.
 * This file adds missing methods to the existing User class.
 */
public class User {
    private Long id;
    private String name;
    private String email;
    private String status;
    private long lastModified;
    private String externalId;
    private String tag;
    
    public User() {
    }
    
    public User(String name, String email) {
        this.name = name;
        this.email = email;
    }
    
    // Existing getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    
    public String getStatus() { return status; }
    public void setStatus(int status) { this.status = String.valueOf(status); }
    
    // ADDED METHODS BELOW
    
    /**
     * Validates the user object.
     * Throws exception if id or email is null.
     */
    public void validate() {
        if (id == null) {
            throw new IllegalStateException("User ID cannot be null");
        }
        if (email == null || email.isEmpty()) {
            throw new IllegalStateException("User email cannot be null or empty");
        }
    }
    
    /**
     * Simulates saving the user to a database.
     */
    public void save() {
        this.lastModified = System.currentTimeMillis();
        // Simulate save operation
    }
    
    /**
     * Sets the last modified timestamp.
     */
    public void setLastModified(long timestamp) {
        this.lastModified = timestamp;
    }
    
    /**
     * Gets the last modified timestamp.
     */
    public long getLastModified() {
        return lastModified;
    }
    
    /**
     * Sets the external ID.
     */
    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }
    
    /**
     * Gets the external ID.
     */
    public String getExternalId() {
        return externalId;
    }
    
    /**
     * Sets a tag for the user.
     */
    public void setTag(String tag) {
        this.tag = tag;
    }
    
    /**
     * Gets the user's tag.
     */
    public String getTag() {
        return tag;
    }
    
    /**
     * String version of status setter.
     */
    public void setStatus(String status) {
        this.status = status;
    }
}
