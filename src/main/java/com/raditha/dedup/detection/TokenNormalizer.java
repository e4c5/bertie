package com.raditha.dedup.detection;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.type.Type;
import com.raditha.dedup.model.Token;
import com.raditha.dedup.model.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes Java statements into semantic tokens.
 * Preserves method names and types while normalizing variables and literals.
 * 
 * Key Design Principle: Avoid over-normalization!
 * - setActive() and setDeleted() are semantically different → preserve method
 * names
 * - User and Customer are different types → preserve type names
 * - userId and customerId are implementation details → normalize to VAR
 */
public class TokenNormalizer {

    /**
     * Normalize a single statement into tokens.
     */
    public List<Token> normalizeStatement(Statement stmt) {
        List<Token> tokens = new ArrayList<>();
        if (stmt == null)
            return tokens;

        // Visit all nodes in the statement
        stmt.walk(node -> {
            Token token = normalizeNode(node);
            if (token != null) {
                tokens.add(token);
            }
        });

        return tokens;
    }

    /**
     * Normalize a list of statements.
     * Used by StatementExtractor for batch processing.
     */
    public List<Token> normalizeStatements(List<Statement> statements) {
        List<Token> allTokens = new ArrayList<>();
        for (Statement stmt : statements) {
            allTokens.addAll(normalizeStatement(stmt));
        }
        return allTokens;
    }

    /**
     * Normalize a single AST node into a token.
     * Returns null for nodes we want to skip.
     */
    private Token normalizeNode(Node node) {
        // Method calls - Check for special types first (assertions, mocks), then
        // generic
        if (node instanceof MethodCallExpr methodCall) {
            String methodName = methodCall.getNameAsString();

            // Check if this is a test assertion
            if (isAssertionMethod(methodName)) {
                return createToken(
                        TokenType.ASSERT,
                        "ASSERT(" + methodName + ")",
                        methodCall.toString(),
                        node);
            }

            // Check if this is a Mockito method
            if (isMockitoMethod(methodName)) {
                return createToken(
                        TokenType.MOCK,
                        "MOCK(" + methodName + ")",
                        methodCall.toString(),
                        node);
            }

            // Generic method call - PRESERVE method name
            return createToken(
                    TokenType.METHOD_CALL,
                    "METHOD_CALL(" + methodName + ")",
                    methodCall.toString(),
                    node);
        }

        // Variable/Field access
        if (node instanceof NameExpr nameExpr) {
            return createToken(
                    TokenType.VAR,
                    "VAR",
                    nameExpr.getNameAsString(),
                    node);
        }

        if (node instanceof FieldAccessExpr fieldAccess) {
            return createToken(
                    TokenType.VAR,
                    "VAR",
                    fieldAccess.toString(),
                    node);
        }

        // Literals
        if (node instanceof StringLiteralExpr) {
            return createToken(
                    TokenType.STRING_LIT,
                    "STRING_LIT",
                    node.toString(),
                    node);
        }

        if (node instanceof IntegerLiteralExpr) {
            return createToken(
                    TokenType.INT_LIT,
                    "INT_LIT",
                    node.toString(),
                    node);
        }

        if (node instanceof LongLiteralExpr) {
            return createToken(
                    TokenType.LONG_LIT,
                    "LONG_LIT",
                    node.toString(),
                    node);
        }

        if (node instanceof DoubleLiteralExpr) {
            return createToken(
                    TokenType.DOUBLE_LIT,
                    "DOUBLE_LIT",
                    node.toString(),
                    node);
        }

        if (node instanceof BooleanLiteralExpr) {
            return createToken(
                    TokenType.BOOLEAN_LIT,
                    "BOOLEAN_LIT",
                    node.toString(),
                    node);
        }

        if (node instanceof NullLiteralExpr) {
            return createToken(
                    TokenType.NULL_LIT,
                    "NULL",
                    "null",
                    node);
        }

        // Type references - PRESERVE type name
        if (node instanceof ClassExpr classExpr) {
            Type type = classExpr.getType();
            String typeName = type.asString();
            return createToken(
                    TokenType.TYPE,
                    "TYPE(" + typeName + ")",
                    typeName,
                    node);
        }

        // Object creation - PRESERVE type
        if (node instanceof ObjectCreationExpr objCreation) {
            String typeName = objCreation.getType().getNameAsString();
            return createToken(
                    TokenType.TYPE,
                    "TYPE(" + typeName + ")",
                    typeName,
                    node);
        }

        // Operators
        if (node instanceof BinaryExpr binaryExpr) {
            String operator = binaryExpr.getOperator().asString();
            return createToken(
                    TokenType.OPERATOR,
                    "OPERATOR(" + operator + ")",
                    operator,
                    node);
        }

        if (node instanceof UnaryExpr unaryExpr) {
            String operator = unaryExpr.getOperator().asString();
            return createToken(
                    TokenType.OPERATOR,
                    "OPERATOR(" + operator + ")",
                    operator,
                    node);
        }

        // Control flow
        if (node instanceof IfStmt) {
            return createToken(TokenType.CONTROL_FLOW, "CONTROL_FLOW(if)", "if", node);
        }

        if (node instanceof ForStmt) {
            return createToken(TokenType.CONTROL_FLOW, "CONTROL_FLOW(for)", "for", node);
        }

        if (node instanceof ForEachStmt) {
            return createToken(TokenType.CONTROL_FLOW, "CONTROL_FLOW(foreach)", "foreach", node);
        }

        if (node instanceof WhileStmt) {
            return createToken(TokenType.CONTROL_FLOW, "CONTROL_FLOW(while)", "while", node);
        }

        if (node instanceof DoStmt) {
            return createToken(TokenType.CONTROL_FLOW, "CONTROL_FLOW(do)", "do", node);
        }

        if (node instanceof SwitchStmt) {
            return createToken(TokenType.CONTROL_FLOW, "CONTROL_FLOW(switch)", "switch", node);
        }

        if (node instanceof TryStmt) {
            return createToken(TokenType.CONTROL_FLOW, "CONTROL_FLOW(try)", "try", node);
        }

        if (node instanceof CatchClause) {
            return createToken(TokenType.CONTROL_FLOW, "CONTROL_FLOW(catch)", "catch", node);
        }

        // Keywords
        if (node instanceof ReturnStmt) {
            return createToken(TokenType.KEYWORD, "KEYWORD(return)", "return", node);
        }

        if (node instanceof ThrowStmt) {
            return createToken(TokenType.KEYWORD, "KEYWORD(throw)", "throw", node);
        }

        if (node instanceof BreakStmt) {
            return createToken(TokenType.KEYWORD, "KEYWORD(break)", "break", node);
        }

        if (node instanceof ContinueStmt) {
            return createToken(TokenType.KEYWORD, "KEYWORD(continue)", "continue", node);
        }

        // Skip other nodes (we don't want to tokenize everything, just semantically
        // meaningful parts)
        return null;
    }

    /**
     * Create a token from a node.
     */
    private Token createToken(TokenType type, String normalizedValue, String originalValue, Node node) {
        int line = node.getRange().map(r -> r.begin.line).orElse(0);
        int column = node.getRange().map(r -> r.begin.column).orElse(0);

        return new Token(type, normalizedValue, originalValue, null, line, column);
    }

    /**
     * Check if method name is a JUnit assertion.
     */
    private boolean isAssertionMethod(String methodName) {
        return methodName.startsWith("assert") ||
                methodName.equals("fail") ||
                methodName.equals("assertTrue") ||
                methodName.equals("assertFalse") ||
                methodName.equals("assertEquals") ||
                methodName.equals("assertNotEquals") ||
                methodName.equals("assertNull") ||
                methodName.equals("assertNotNull") ||
                methodName.equals("assertThrows") ||
                methodName.equals("assertThat");
    }

    /**
     * Check if method name is a Mockito method.
     */
    private boolean isMockitoMethod(String methodName) {
        return methodName.equals("when") ||
                methodName.equals("verify") ||
                methodName.equals("mock") ||
                methodName.equals("spy") ||
                methodName.equals("doReturn") ||
                methodName.equals("doThrow") ||
                methodName.equals("doAnswer") ||
                methodName.equals("doNothing") ||
                methodName.equals("thenReturn") ||
                methodName.equals("thenThrow") ||
                methodName.equals("times") ||
                methodName.equals("never") ||
                methodName.equals("any") ||
                methodName.equals("anyString") ||
                methodName.equals("anyInt") ||
                methodName.equals("anyLong");
    }
}
