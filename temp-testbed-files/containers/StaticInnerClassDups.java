package com.raditha.bertie.testbed.containers;

import com.raditha.bertie.testbed.model.User;
import com.raditha.bertie.testbed.model.Database;

/**
 * Test class for detecting duplicate code in static inner class methods.
 * Tests that duplicate detection and refactoring work in static nested classes.
 */
public class StaticInnerClassDups {
    
    // Static inner class with duplicate validation methods
    public static class UserValidator {
        
        public static boolean isValidEmail(String email) {
            // Duplicate Block 1
            if (email == null || email.isEmpty()) {
                return false;
            }
            if (!email.contains("@")) {
                return false;
            }
            if (email.length() < 5) {
                return false;
            }
            return true;
        }
        
        public static boolean isValidName(String name) {
            // Duplicate Block 2 (similar to Block 1)
            if (name == null || name.isEmpty()) {
                return false;
            }
            if (!name.matches("[a-zA-Z ]+")) {
                return false;
            }
            if (name.length() < 2) {
                return false;
            }
            return true;
        }
    }
    
    // Another static inner class with similar duplicates
    public static class DatabaseConfig {
        
        public static String buildConnectionString(String host, int port, String database) {
            // Duplicate Block 3
            if (host == null || host.isEmpty()) {
                throw new IllegalArgumentException("Invalid parameter");
            }
            if (database == null || database.isEmpty()) {
                throw new IllegalArgumentException("Invalid parameter");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid parameter");
            }
            return "jdbc:postgresql://" + host + ":" + port + "/" + database;
        }
        
        public static String buildTestConnectionString(String host, int port) {
            // Duplicate Block 4 (similar to Block 3)
            if (host == null || host.isEmpty()) {
                throw new IllegalArgumentException("Invalid parameter");
            }
            if ("".equals(host.trim())) {
                throw new IllegalArgumentException("Invalid parameter");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid parameter");
            }
            return "jdbc:postgresql://" + host + ":" + port + "/testdb";
        }
    }
    
    public static boolean validateUser(User user) {
        return UserValidator.isValidEmail(user.getEmail()) &&
               UserValidator.isValidName(user.getName());
    }
    
    public static String getConnection(String host) {
        return DatabaseConfig.buildConnectionString(host, 5432, "production");
    }
}
