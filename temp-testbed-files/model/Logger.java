package com.raditha.bertie.testbed.model;

/**
 * Logger model class with methods needed for container duplicate tests.
 * This file adds missing methods to the existing Logger class.
 */
public class Logger {
    private String name;
    private String level;
    private String format;
    private boolean enabled;
    
    public Logger(String name) {
        this.name = name;
        this.level = "INFO";
        this.enabled = true;
    }
    
    // Existing methods
    public void info(String message) {
        if (enabled) {
            System.out.println("[" + level + "] " + name + ": " + message);
        }
    }
    
    public void error(String message) {
        if (enabled) {
            System.err.println("[ERROR] " + name + ": " + message);
        }
    }
    
    // ADDED METHODS BELOW
    
    /**
     * Logs a debug message.
     */
    public void debug(String message) {
        if (enabled && "DEBUG".equals(level)) {
            System.out.println("[DEBUG] " + name + ": " + message);
        }
    }
    
    /**
     * Sets the logging level.
     */
    public void setLevel(String level) {
        this.level = level;
    }
    
    /**
     * Gets the logging level.
     */
    public String getLevel() {
        return level;
    }
    
    /**
     * Sets the log format.
     */
    public void setFormat(String format) {
        this.format = format;
    }
    
    /**
     * Gets the log format.
     */
    public String getFormat() {
        return format;
    }
    
    /**
     * Enables the logger.
     */
    public void enable() {
        this.enabled = true;
    }
    
    /**
     * Disables the logger.
     */
    public void disable() {
        this.enabled = false;
    }
    
    /**
     * Checks if the logger is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }
}
