package com.raditha.bertie.testbed.containers;

import java.util.ArrayList;
import java.util.List;

/**
 * Test file for duplicate detection in static initializer blocks.
 * Demonstrates STATIC_INITIALIZER container type support.
 */
public class StaticInitializerDups {
    
    private static List<String> configItems = new ArrayList<>();
    private static int maxRetries = 3;
    private static String environment;
    
    // Static initializer block #1 with duplicate code
    static {
        // DUPLICATE BLOCK START
        configItems.clear();
        configItems.add("item1");
        configItems.add("item2");
        configItems.add("item3");
        maxRetries = 5;
        environment = "production";
        System.out.println("Configuration loaded: " + configItems.size() + " items");
        // DUPLICATE BLOCK END
    }
    
    // Static initializer block #2 with duplicate code
    static {
        // DUPLICATE BLOCK START
        configItems.clear();
        configItems.add("item1");
        configItems.add("item2");
        configItems.add("item3");
        maxRetries = 5;
        environment = "production";
        System.out.println("Configuration loaded: " + configItems.size() + " items");
        // DUPLICATE BLOCK END
    }
    
    public static void initialize() {
        // Another duplicate in a method for comparison
        configItems.clear();
        configItems.add("item1");
        configItems.add("item2");
        configItems.add("item3");
        maxRetries = 5;
        environment = "production";
        System.out.println("Configuration loaded: " + configItems.size() + " items");
    }
    
    public static List<String> getConfigItems() {
        return configItems;
    }
    
    public static String getEnvironment() {
        return environment;
    }
}
