package com.raditha.bertie.testbed.model;

/**
 * Database model class with methods needed for container duplicate tests.
 * This file adds missing methods to the existing Database class.
 */
public class Database {
    private String host;
    private int port;
    private String database;
    private String connectionString;
    private int maxConnections;
    private boolean poolingEnabled;
    
    public Database() {
        this.port = 5432;
        this.maxConnections = 10;
        this.poolingEnabled = false;
    }
    
    // ADDED METHODS BELOW
    
    /**
     * Sets the database host.
     */
    public void setHost(String host) {
        this.host = host;
    }
    
    /**
     * Sets the database port.
     */
    public void setPort(int port) {
        this.port = port;
    }
    
    /**
     * Sets the database name.
     */
    public void setDatabase(String database) {
        this.database = database;
    }
    
    /**
     * Connects to the database.
     */
    public void connect(String connectionString) {
        this.connectionString = connectionString;
        // Simulate connection
    }
    
    /**
     * Gets the connection string.
     */
    public String getConnectionString() {
        return connectionString;
    }
    
    /**
     * Sets the maximum number of connections.
     */
    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }
    
    /**
     * Gets the maximum number of connections.
     */
    public int getMaxConnections() {
        return maxConnections;
    }
    
    /**
     * Enables connection pooling.
     */
    public void enablePooling() {
        this.poolingEnabled = true;
    }
    
    /**
     * Disables connection pooling.
     */
    public void disablePooling() {
        this.poolingEnabled = false;
    }
    
    /**
     * Checks if pooling is enabled.
     */
    public boolean isPoolingEnabled() {
        return poolingEnabled;
    }
}
