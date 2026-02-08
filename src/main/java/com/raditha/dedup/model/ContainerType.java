package com.raditha.dedup.model;

/**
 * Enum representing the type of container that holds a statement sequence.
 * This enables support for different code constructs beyond methods and constructors.
 */
public enum ContainerType {
    /**
     * Regular method body.
     */
    METHOD,
    
    /**
     * Constructor body.
     */
    CONSTRUCTOR,
    
    /**
     * Static initializer block: {@code static { ... }}
     */
    STATIC_INITIALIZER,
    
    /**
     * Instance initializer block: {@code { ... }}
     */
    INSTANCE_INITIALIZER,
    
    /**
     * Block-bodied lambda expression: {@code (args) -> { ... }}
     */
    LAMBDA,
    
    /**
     * Method inside an anonymous class: {@code new Interface() { void method() { ... } }}
     */
    ANONYMOUS_CLASS_METHOD;
    
    /**
     * Check if this container type represents a static context.
     * @return true if the container is always in a static context
     */
    public boolean isDefinitelyStatic() {
        return this == STATIC_INITIALIZER;
    }
    
    /**
     * Check if this container type is a callable (method or constructor).
     * @return true if the container is a callable declaration
     */
    public boolean isCallable() {
        return this == METHOD || this == CONSTRUCTOR || this == ANONYMOUS_CLASS_METHOD;
    }
    
    /**
     * Check if this container type is an initializer block.
     * @return true if the container is a static or instance initializer
     */
    public boolean isInitializer() {
        return this == STATIC_INITIALIZER || this == INSTANCE_INITIALIZER;
    }
}
