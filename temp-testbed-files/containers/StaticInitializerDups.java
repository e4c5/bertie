package com.raditha.bertie.testbed.containers;

import com.raditha.bertie.testbed.model.User;
import com.raditha.bertie.testbed.model.Logger;

/**
 * Test class for detecting duplicate code in static initializer blocks.
 * Contains intentional duplicates that should be detected and refactored.
 */
public class StaticInitializerDups {
    
    private static Logger logger;
    private static User adminUser;
    private static String configPath;
    
    // Static initializer with duplicate validation logic
    static {
        logger = new Logger("StaticInit1");
        
        // Duplicate Block 1
        if (logger == null) {
            throw new IllegalStateException("Logger cannot be null");
        }
        logger.setLevel("INFO");
        logger.setFormat("JSON");
        logger.enable();
    }
    
    // Another static initializer with similar validation
    static {
        adminUser = new User();
        adminUser.setName("Admin");
        
        // Duplicate Block 2 (similar to Block 1)
        if (adminUser == null) {
            throw new IllegalStateException("Logger cannot be null");
        }
        adminUser.setStatus("ACTIVE");
        adminUser.setTag("ADMIN");
        adminUser.validate();
    }
    
    // Third static initializer with configuration
    static {
        configPath = System.getProperty("config.path", "/etc/app/config");
        
        // Duplicate Block 3 (similar pattern)
        if (configPath == null) {
            throw new IllegalStateException("Logger cannot be null");
        }
        configPath = configPath.trim();
        configPath = configPath.toLowerCase();
    }
    
    public static Logger getLogger() {
        return logger;
    }
    
    public static User getAdminUser() {
        return adminUser;
    }
    
    public static String getConfigPath() {
        return configPath;
    }
}
