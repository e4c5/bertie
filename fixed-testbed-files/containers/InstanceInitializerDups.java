package com.raditha.bertie.testbed.containers;

import java.util.HashMap;
import java.util.Map;

/**
 * Test file for duplicate detection in instance initializer blocks.
 * Demonstrates INSTANCE_INITIALIZER container type support.
 */
public class InstanceInitializerDups {
    
    private Map<String, Object> properties = new HashMap<>();
    private String instanceId;
    private long createdAt;
    
    // Instance initializer block #1 with duplicate code
    {
        // DUPLICATE BLOCK START
        properties.put("version", "1.0");
        properties.put("active", true);
        properties.put("timeout", 30);
        instanceId = "instance-" + System.currentTimeMillis();
        createdAt = System.currentTimeMillis();
        System.out.println("Instance initialized with " + properties.size() + " properties");
        // DUPLICATE BLOCK END
    }
    
    // Instance initializer block #2 with duplicate code
    {
        // DUPLICATE BLOCK START
        properties.put("version", "1.0");
        properties.put("active", true);
        properties.put("timeout", 30);
        instanceId = "instance-" + System.currentTimeMillis();
        createdAt = System.currentTimeMillis();
        System.out.println("Instance initialized with " + properties.size() + " properties");
        // DUPLICATE BLOCK END
    }
    
    public InstanceInitializerDups() {
        // Constructor with another duplicate
        properties.put("version", "1.0");
        properties.put("active", true);
        properties.put("timeout", 30);
        instanceId = "instance-" + System.currentTimeMillis();
        createdAt = System.currentTimeMillis();
        System.out.println("Instance initialized with " + properties.size() + " properties");
    }
    
    public void reset() {
        // Method with duplicate code
        properties.put("version", "1.0");
        properties.put("active", true);
        properties.put("timeout", 30);
        instanceId = "instance-" + System.currentTimeMillis();
        createdAt = System.currentTimeMillis();
        System.out.println("Instance initialized with " + properties.size() + " properties");
    }
    
    public Map<String, Object> getProperties() {
        return properties;
    }
    
    public String getInstanceId() {
        return instanceId;
    }
}
