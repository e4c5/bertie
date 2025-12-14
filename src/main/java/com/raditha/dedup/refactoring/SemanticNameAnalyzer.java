package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.StatementSequence;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Analyzes code content to generate meaningful method names.
 * Extracts verbs from method calls and objects from variables.
 */
public class SemanticNameAnalyzer {

    private static final Set<String> COMMON_VERBS = Set.of(
            "get", "set", "create", "update", "delete", "save", "load",
            "validate", "check", "process", "calculate", "compute",
            "build", "generate", "parse", "format", "convert",
            "find", "search", "filter", "sort", "add", "remove");

    private static final Set<String> NOISE_WORDS = Set.of(
            "the", "a", "an", "this", "that", "for", "to", "from", "with", "by");

    /**
     * Attempt to generate a semantic name from code analysis.
     * Returns null if unable to generate meaningful name.
     */
    public String generateName(StatementSequence sequence) {
        List<String> verbs = new ArrayList<>();
        List<String> objects = new ArrayList<>();

        // Analyze each statement
        for (Statement stmt : sequence.statements()) {
            extractVerbsAndObjects(stmt, verbs, objects);
        }

        // Try to build a meaningful name
        return buildMethodName(verbs, objects);
    }

    private void extractVerbsAndObjects(Statement stmt, List<String> verbs, List<String> objects) {
        // Extract from method calls
        stmt.findAll(MethodCallExpr.class).forEach(mce -> {
            String methodName = mce.getNameAsString();

            // Check if method name starts with common verb
            for (String verb : COMMON_VERBS) {
                if (methodName.toLowerCase().startsWith(verb)) {
                    if (!verbs.contains(verb)) {
                        verbs.add(verb);
                    }

                    // Extract object from method name (e.g., "setName" -> "name")
                    String remainder = methodName.substring(verb.length());
                    if (!remainder.isEmpty() && Character.isUpperCase(remainder.charAt(0))) {
                        String object = Character.toLowerCase(remainder.charAt(0)) + remainder.substring(1);
                        if (!objects.contains(object) && !isNoiseWord(object)) {
                            objects.add(object);
                        }
                    }
                    break;
                }
            }
        });

        // Extract from variable declarations
        stmt.findAll(VariableDeclarationExpr.class).forEach(vde -> {
            vde.getVariables().forEach(v -> {
                String type = v.getType().asString();
                String name = v.getNameAsString();

                // Prefer type name over variable name (User vs user)
                String object = extractObjectName(type);
                if (object != null && !objects.contains(object) && !isNoiseWord(object)) {
                    objects.add(object);
                } else {
                    object = extractObjectName(name);
                    if (object != null && !objects.contains(object) && !isNoiseWord(object)) {
                        objects.add(object);
                    }
                }
            });
        });
    }

    private String extractObjectName(String str) {
        if (str == null || str.isEmpty()) {
            return null;
        }

        // Remove common prefixes/suffixes
        str = str.replaceAll("(?i)(dto|entity|model|bean|vo|request|response)$", "");

        // Extract the main noun (capitalize first letter)
        if (!str.isEmpty()) {
            return Character.toUpperCase(str.charAt(0)) + str.substring(1);
        }

        return null;
    }

    private boolean isNoiseWord(String word) {
        return NOISE_WORDS.contains(word.toLowerCase());
    }

    private String buildMethodName(List<String> verbs, List<String> objects) {
        // Need at least a verb to build a name
        if (verbs.isEmpty()) {
            return null;
        }

        StringBuilder name = new StringBuilder();

        // Start with primary verb
        name.append(verbs.get(0));

        // Add primary object if available
        if (!objects.isEmpty()) {
            name.append(capitalize(objects.get(0)));
        }

        // If name is too generic, try adding more context
        if (objects.size() > 1 && name.length() < 15) {
            name.append("And").append(capitalize(objects.get(1)));
        }

        // Validate the generated name
        String result = name.toString();

        // Must be valid Java identifier
        if (!isValidJavaIdentifier(result)) {
            return null;
        }

        // Should be reasonable length
        if (result.length() > 40) {
            return null;
        }

        // Should not be too generic
        if (result.equals("get") || result.equals("set") || result.equals("process")) {
            return null;
        }

        return result;
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private boolean isValidJavaIdentifier(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }

        if (!Character.isJavaIdentifierStart(str.charAt(0))) {
            return false;
        }

        for (int i = 1; i < str.length(); i++) {
            if (!Character.isJavaIdentifierPart(str.charAt(i))) {
                return false;
            }
        }

        return true;
    }
}
