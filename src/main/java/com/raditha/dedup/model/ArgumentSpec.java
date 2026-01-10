package com.raditha.dedup.model;

import com.github.javaparser.ast.type.Type;

/**
 * Specification for an argument to pass to an extracted method.
 * Represents a variable reference that should be passed through.
 * 
 * @param name  Variable name
 * @param type  Type of the variable
 * @param scope Scope of the variable (parameter, local, field)
 */
public record ArgumentSpec(
        String name,
        Type type,
        Scope scope) {
}
