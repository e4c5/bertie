package com.raditha.dedup.normalization;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes AST for fuzzy duplicate detection.
 * Replaces literal values with type placeholders while preserving structure.
 * 
 * Example:
 * Original: user.setName("Alice");
 * Normalized: user.setName(STRING_LIT);
 */
public class ASTNormalizer {

    /**
     * Normalize a list of statements for comparison.
     * 
     * @param statements Statements to normalize
     * @return List of normalized nodes containing both normalized and original AST
     */
    public List<NormalizedNode> normalize(List<Statement> statements) {
        List<NormalizedNode> result = new ArrayList<>();

        for (Statement stmt : statements) {
            Statement normalized = normalizeStatement(stmt);
            result.add(new NormalizedNode(normalized, stmt));
        }

        return result;
    }

    /**
     * Normalize a single statement by replacing all literals with placeholders.
     * 
     * @param original Original statement
     * @return Normalized statement (cloned with literals replaced)
     */
    private Statement normalizeStatement(Statement original) {
        // Clone to avoid modifying original
        Statement clone = original.clone();

        // Replace all literal expressions with placeholders
        replaceLiterals(clone);

        return clone;
    }

    /**
     * Replace all literal expressions in a node with type placeholders.
     * Preserves AST structure (e.g., BinaryExpr for concatenation).
     * 
     * @param node Node to process (modified in place)
     */
    private void replaceLiterals(Node node) {
        // String literals → STRING_LIT
        node.findAll(StringLiteralExpr.class).forEach(lit -> {
            lit.replace(new NameExpr("STRING_LIT"));
        });

        // Integer literals → INT_LIT
        node.findAll(IntegerLiteralExpr.class).forEach(lit -> {
            lit.replace(new NameExpr("INT_LIT"));
        });

        // Long literals → LONG_LIT
        node.findAll(LongLiteralExpr.class).forEach(lit -> {
            lit.replace(new NameExpr("LONG_LIT"));
        });

        // Double literals → DOUBLE_LIT
        node.findAll(DoubleLiteralExpr.class).forEach(lit -> {
            lit.replace(new NameExpr("DOUBLE_LIT"));
        });

        // Boolean literals → BOOL_LIT
        node.findAll(BooleanLiteralExpr.class).forEach(lit -> {
            lit.replace(new NameExpr("BOOL_LIT"));
        });

        // Null literals → NULL_LIT
        node.findAll(NullLiteralExpr.class).forEach(lit -> {
            lit.replace(new NameExpr("NULL_LIT"));
        });

        node.findAll(CharLiteralExpr.class).forEach(lit -> {
            lit.replace(new NameExpr("CHAR_LIT"));
        });
    }

    /**
     * Normalize a list of statements for fuzzy comparison (anonymizing
     * identifiers).
     * 
     * @param statements Statements to normalize
     * @return List of normalized nodes with anonymized identifiers and literals
     */
    public List<NormalizedNode> normalizeFuzzy(List<Statement> statements) {
        List<NormalizedNode> result = new ArrayList<>();

        for (Statement stmt : statements) {
            Statement clone = stmt.clone();
            replaceLiterals(clone);
            replaceIdentifiers(clone);
            result.add(new NormalizedNode(clone, stmt));
        }

        return result;
    }

    /**
     * Replace identifiers with generic placeholders for structural comparison.
     */
    private void replaceIdentifiers(Node node) {
        // Variable names -> VAR
        node.findAll(NameExpr.class).forEach(n -> {
            // Skip if it's a replacement placeholder already (e.g. STRING_LIT)
            if (!isPlaceholder(n.getNameAsString())) {
                n.replace(new NameExpr("VAR"));
            }
        });

        // Method calls -> METHOD
        node.findAll(MethodCallExpr.class).forEach(m -> m.setName(new SimpleName("METHOD")));

        // Field access -> FIELD
        node.findAll(FieldAccessExpr.class).forEach(f -> f.setName(new SimpleName("FIELD")));

        // Variable declarations -> VAR
        node.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)
                .forEach(v -> v.setName(new SimpleName("VAR")));

        // Parameters -> VAR
        node.findAll(com.github.javaparser.ast.body.Parameter.class).forEach(p -> p.setName(new SimpleName("VAR")));
    }

    private boolean isPlaceholder(String name) {
        return name.endsWith("_LIT") || name.equals("VAR") || name.equals("METHOD") || name.equals("FIELD");
    }
}
