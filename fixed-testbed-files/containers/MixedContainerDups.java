package com.raditha.bertie.testbed.containers;

import java.util.function.Consumer;

/**
 * Test file for duplicate detection across multiple container types.
 * Demonstrates cross-container duplicate detection and refactoring.
 */
public class MixedContainerDups {
    
    private static String staticConfig;
    private String instanceConfig;
    
    // Static initializer with duplicate
    static {
        // DUPLICATE BLOCK START
        String prefix = "CONFIG_";
        int version = 1;
        String suffix = "_FINAL";
        String config = prefix + version + suffix;
        System.out.println("Configuration: " + config);
        // DUPLICATE BLOCK END
        staticConfig = "initialized";
    }
    
    // Instance initializer with duplicate
    {
        // DUPLICATE BLOCK START
        String prefix = "CONFIG_";
        int version = 1;
        String suffix = "_FINAL";
        String config = prefix + version + suffix;
        System.out.println("Configuration: " + config);
        // DUPLICATE BLOCK END
        instanceConfig = "initialized";
    }
    
    public MixedContainerDups() {
        // Constructor with duplicate
        String prefix = "CONFIG_";
        int version = 1;
        String suffix = "_FINAL";
        String config = prefix + version + suffix;
        System.out.println("Configuration: " + config);
    }
    
    public void instanceMethod() {
        // Instance method with duplicate
        String prefix = "CONFIG_";
        int version = 1;
        String suffix = "_FINAL";
        String config = prefix + version + suffix;
        System.out.println("Configuration: " + config);
    }
    
    public static void staticMethod() {
        // Static method with duplicate
        String prefix = "CONFIG_";
        int version = 1;
        String suffix = "_FINAL";
        String config = prefix + version + suffix;
        System.out.println("Configuration: " + config);
    }
    
    public void useLambda() {
        Consumer<String> consumer = (input) -> {
            // Lambda with duplicate
            String prefix = "CONFIG_";
            int version = 1;
            String suffix = "_FINAL";
            String config = prefix + version + suffix;
            System.out.println("Configuration: " + config);
        };
        consumer.accept("test");
    }
    
    // Inner class with duplicate
    public class InnerClass {
        public void innerMethod() {
            String prefix = "CONFIG_";
            int version = 1;
            String suffix = "_FINAL";
            String config = prefix + version + suffix;
            System.out.println("Configuration: " + config);
        }
    }
    
    public static void main(String[] args) {
        MixedContainerDups test = new MixedContainerDups();
        test.instanceMethod();
        staticMethod();
        test.useLambda();
        
        InnerClass inner = test.new InnerClass();
        inner.innerMethod();
    }
}
