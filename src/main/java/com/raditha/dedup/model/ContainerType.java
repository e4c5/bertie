package com.raditha.dedup.model;

/**
 * Represents the type of container that holds a statement sequence.
 * This allows Bertie to handle duplicates in various Java constructs beyond just methods.
 */
public enum ContainerType {
    /**
     * Regular method declaration.
     */
    METHOD,
    
    /**
     * Constructor declaration.
     */
    CONSTRUCTOR,
    
    /**
     * Static initializer block (static { ... }).
     */
    STATIC_INITIALIZER,
    
    /**
     * Instance initializer block ({ ... }).
     */
    INSTANCE_INITIALIZER,
    
    /**
     * Lambda expression with block body.
     */
    LAMBDA,
    
    /**
     * Initializer within an anonymous class.
     * (For future support of fields/initializers in anonymous classes)
     */
    ANONYMOUS_CLASS_INITIALIZER
}
