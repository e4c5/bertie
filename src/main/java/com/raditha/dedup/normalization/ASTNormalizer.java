package com.raditha.dedup.normalization;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;

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

        // One pass visitor: literals-only
        clone.accept(new NormalizingVisitor(false), null);

        return clone;
    }

    /**
     * Normalize a list of statements for fuzzy comparison (anonymizing
     * identifiers as well as literals).
     *
     * @param statements Statements to normalize
     * @return List of normalized nodes with anonymized identifiers and literals
     */
    public List<NormalizedNode> normalizeFuzzy(List<Statement> statements) {
        List<NormalizedNode> result = new ArrayList<>();

        for (Statement stmt : statements) {
            Statement clone = stmt.clone();
            // One pass visitor: literals + identifiers
            clone.accept(new NormalizingVisitor(true), null);
            result.add(new NormalizedNode(clone, stmt));
        }

        return result;
    }


    // Private inner visitor implementing single-pass normalization
    private static class NormalizingVisitor extends ModifierVisitor<Void> {
        private final boolean includeIdentifiers;

        private NormalizingVisitor(boolean includeIdentifiers) {
            this.includeIdentifiers = includeIdentifiers;
        }


        private static boolean isPlaceholder(String name) {
            return name.equals("STRING_LIT")
                    || name.equals("INT_LIT")
                    || name.equals("LONG_LIT")
                    || name.equals("DOUBLE_LIT")
                    || name.equals("BOOL_LIT")
                    || name.equals("NULL_LIT")
                    || name.equals("CHAR_LIT")
                    || name.equals("VAR")
                    || name.equals("METHOD")
                    || name.equals("FIELD");
        }

        // --- Literals → placeholders ---
        @Override
        public Visitable visit(StringLiteralExpr n, Void arg) {
            return new NameExpr("STRING_LIT");
        }

        @Override
        public Visitable visit(IntegerLiteralExpr n, Void arg) {
            return new NameExpr("INT_LIT");
        }

        @Override
        public Visitable visit(LongLiteralExpr n, Void arg) {
            return new NameExpr("LONG_LIT");
        }

        @Override
        public Visitable visit(DoubleLiteralExpr n, Void arg) {
            return new NameExpr("DOUBLE_LIT");
        }

        @Override
        public Visitable visit(BooleanLiteralExpr n, Void arg) {
            return new NameExpr("BOOL_LIT");
        }

        @Override
        public Visitable visit(NullLiteralExpr n, Void arg) {
            return new NameExpr("NULL_LIT");
        }

        @Override
        public Visitable visit(CharLiteralExpr n, Void arg) {
            return new NameExpr("CHAR_LIT");
        }

        // --- Identifiers → placeholders (optional) ---
        @Override
        public Visitable visit(NameExpr n, Void arg) {
            if (includeIdentifiers && !isPlaceholder(n.getNameAsString())) {
                return new NameExpr("VAR");
            }
            return super.visit(n, arg);
        }

        @Override
        public Visitable visit(MethodCallExpr n, Void arg) {
            // Visit scope/args first
            super.visit(n, arg);
            // Do NOT normalize method names!
            // Design requires preserving "save", "delete", etc. for semantic meaning.
            // Only the scope (variable) and arguments are normalized via their own visit methods.
            return n;
        }

        @Override
        public Visitable visit(FieldAccessExpr n, Void arg) {
            super.visit(n, arg);
            if (includeIdentifiers) {
                n.setName("FIELD");
            }
            return n;
        }

        @Override
        public Visitable visit(VariableDeclarator n, Void arg) {
            super.visit(n, arg);
            if (includeIdentifiers) {
                n.setName("VAR");
            }
            return n;
        }

        @Override
        public Visitable visit(Parameter n, Void arg) {
            super.visit(n, arg);
            if (includeIdentifiers) {
                n.setName("VAR");
            }
            return n;
        }
    }
}
