package com.raditha.dedup.normalization;

import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rapidly tokenizes statements for LSH without AST cloning or pretty-printing.
 * Emulates the fuzzy normalization logic of ASTNormalizer but emits strings directly.
 * 
 * OPTIMIZATION: Uses statement-level caching to avoid redundant tokenization
 * of the same statement nodes in overlapping windows.
 */
public class FuzzyTokenizer {

    /**
     * Cache for individual statement tokens.
     * Uses IdentityHashMap for reference equality (same AST node).
     */
    private final Map<Statement, List<String>> statementCache = new IdentityHashMap<>();

    /**
     * Generate fuzzy tokens for a single statement.
     * Results are cached to avoid redundant AST traversal.
     * Returns an unmodifiable view to prevent cache corruption.
     */
    public List<String> tokenizeStatement(Statement statement) {
        List<String> tokens = statementCache.computeIfAbsent(statement, stmt -> {
            List<String> newTokens = new ArrayList<>();
            TokenizingVisitor visitor = new TokenizingVisitor(newTokens);
            stmt.accept(visitor, null);
            return newTokens;
        });
        return java.util.Collections.unmodifiableList(tokens);
    }

    /**
     * Generate fuzzy tokens for a list of statements.
     * Uses cached tokens for individual statements to avoid redundant work.
     */
    public List<String> tokenize(List<Statement> statements) {
        List<String> tokens = new ArrayList<>();
        for (Statement stmt : statements) {
            tokens.addAll(tokenizeStatement(stmt));
        }
        return tokens;
    }

    /**
     * Clear the statement cache.
     * Should be called between projects to prevent memory leaks.
     */
    public void clearCache() {
        statementCache.clear();
    }

    private static class TokenizingVisitor extends VoidVisitorAdapter<Void> {
        private final List<String> tokens;

        public TokenizingVisitor(List<String> tokens) {
            this.tokens = tokens;
        }

        private void addToken(String token) {
            tokens.add(token);
        }

        // --- Literals ---

        @Override
        public void visit(StringLiteralExpr n, Void arg) {
            addToken("STRING_LIT");
        }

        @Override
        public void visit(IntegerLiteralExpr n, Void arg) {
            addToken("INT_LIT");
        }

        @Override
        public void visit(LongLiteralExpr n, Void arg) {
            addToken("LONG_LIT");
        }

        @Override
        public void visit(DoubleLiteralExpr n, Void arg) {
            addToken("DOUBLE_LIT");
        }

        @Override
        public void visit(BooleanLiteralExpr n, Void arg) {
            addToken("BOOL_LIT");
        }

        @Override
        public void visit(NullLiteralExpr n, Void arg) {
            addToken("NULL_LIT");
        }

        @Override
        public void visit(CharLiteralExpr n, Void arg) {
            addToken("CHAR_LIT");
        }

        // --- Identifiers ---

        private static boolean isPlaceholder(String name) {
            return name.equals("STRING_LIT") || name.equals("INT_LIT") ||
                    name.equals("VAR") || name.equals("METHOD") || name.equals("FIELD");
        }

        @Override
        public void visit(NameExpr n, Void arg) {
            if (!isPlaceholder(n.getNameAsString())) {
                addToken("VAR");
            } else {
                addToken(n.getNameAsString());
            }
        }

        @Override
        public void visit(FieldAccessExpr n, Void arg) {
            // Visit scope
            if (n.getScope() != null) {
                n.getScope().accept(this, arg);
            }
            // Normalize field name
            addToken("FIELD");
        }

        @Override
        public void visit(VariableDeclarator n, Void arg) {
            // Type
            n.getType().accept(this, arg);
            // Name -> VAR
            addToken("VAR");
            // Initializer
            if (n.getInitializer().isPresent()) {
                addToken("=");
                n.getInitializer().get().accept(this, arg);
            }
        }

        @Override
        public void visit(Parameter n, Void arg) {
            n.getType().accept(this, arg);
            addToken("VAR");
        }

        @Override
        public void visit(MethodCallExpr n, Void arg) {
            // Scope
            if (n.getScope().isPresent()) {
                n.getScope().get().accept(this, arg);
                addToken(".");
            }
            // Method Name (PRESERVED)
            addToken(n.getNameAsString());
            // Arguments
            addToken("(");
            for (Expression e : n.getArguments()) {
                e.accept(this, arg);
                addToken(","); // Simple separator
            }
            addToken(")");
        }

        // --- Structural Elements (Simplistic visitor for structure) ---
        // We rely on VoidVisitorAdapter to visit children, but we might want to emit tokens for structure too
        // For LSH, the tokens sequence matters.
        // If we only emit leaf tokens, we capture content.
        // Let's add basic structural tokens to distinguish code shapes.

        @Override
        public void visit(com.github.javaparser.ast.stmt.IfStmt n, Void arg) {
            addToken("if");
            n.getCondition().accept(this, arg);
            n.getThenStmt().accept(this, arg);
            if (n.getElseStmt().isPresent()) {
                addToken("else");
                n.getElseStmt().get().accept(this, arg);
            }
        }

        @Override
        public void visit(com.github.javaparser.ast.stmt.ForStmt n, Void arg) {
            addToken("for");
            super.visit(n, arg);
        }

        @Override
        public void visit(com.github.javaparser.ast.stmt.WhileStmt n, Void arg) {
            addToken("while");
            super.visit(n, arg);
        }

        @Override
        public void visit(com.github.javaparser.ast.stmt.ReturnStmt n, Void arg) {
            addToken("return");
            super.visit(n, arg);
        }

         @Override
        public void visit(com.github.javaparser.ast.stmt.BlockStmt n, Void arg) {
             addToken("{");
             super.visit(n, arg);
             addToken("}");
        }
    }
}
