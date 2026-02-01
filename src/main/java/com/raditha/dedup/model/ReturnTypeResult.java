package com.raditha.dedup.model;

import com.github.javaparser.ast.type.Type;

/**
 * Result of return type resolution.
 * 
 * @param returnType The resolved return type
 * @param returnVariable The name of the variable to return (if applicable)
 */
public record ReturnTypeResult(Type returnType, String returnVariable) {
}
