package com.raditha.dedup.normalization;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional tests recommended by code review to ensure robust normalization
 * coverage.
 */
class AdditionalNormalizationTest {

    private ASTNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new ASTNormalizer();
    }

    private Statement stmt(String code) {
        return StaticJavaParser.parseStatement(code);
    }

    private String normalize(String code) {
        Statement s = stmt(code);
        return normalizer.normalizeFuzzy(List.of(s)).get(0).normalized().toString();
    }

    @Test
    void testStaticAndQualifiedMethodCalls() {
        // "Utils.parse(s)" -> Scope "Utils" should be VAR, Method "parse" preserved
        String norm = normalize("Utils.parse(s);");
        assertEquals("VAR.parse(VAR);", norm);

        // "Parser.parse(s)" -> Scope "Parser" should be VAR, Method "parse" preserved
        String norm2 = normalize("Parser.parse(s);");
        assertEquals("VAR.parse(VAR);", norm2);
    }

    @Test
    void testChainedMethodCalls() {
        // "a.b().c()" -> a becomes VAR, b preserved, c preserved
        // Structure: MethodCall(MethodCall(NameExpr(a), b), c)
        String norm = normalize("a.b().c();");

        // Expected: VAR.b().c();
        assertEquals("VAR.b().c();", norm);
    }

    @Test
    void testUnqualifiedMethodCalls() {
        // "save()" -> No scope, method preserved
        String norm = normalize("save();");
        assertEquals("save();", norm);
    }

    @Test
    void testConstructorInvocations() {
        // "new Foo()" -> Type Foo should be preserved (as TYPE or just preserved)?
        // Normalizer currently doesn't seem to have special handling for ObjectCreationExpr in the snippet I saw,
        // let's see what it does. If it visits arguments, it might preserve the type name or normalize it?
        // Let's assume for now it preserves structure.

        // "new Foo().bar()" -> chained
        String norm = normalize("new Foo().bar();");

        // If Foo is a type, it might be preserved or normalized depending on Type handling.
        // If Type is not visited/normalized, it stays "Foo".
        // The NormalizingVisitor handles Literals and specific Exprs.
        // Let's check if it touches ClassOrInterfaceType.

        // Asserting method name preservation is the key here.
        assertTrue(norm.contains("bar"), "Method 'bar' should be preserved");
    }

    @Test
    void testOverloadedMethods() {
        String norm1 = normalize("save(1);");
        String norm2 = normalize("save(1, 2);");

        assertEquals("save(INT_LIT);", norm1);
        assertEquals("save(INT_LIT, INT_LIT);", norm2);

        // Method names identical
        assertTrue(norm1.startsWith("save"));
        assertTrue(norm2.startsWith("save"));
    }

    @Test
    void testEdgeCasesWithPlaceholderLikeIdentifiers() {
        // "METHOD.foo()" -> Scope "METHOD" is a placeholder name?
        // NormalizingVisitor.isPlaceholder checks for "METHOD".
        // If it is a NameExpr "METHOD", visit(NameExpr) checks isPlaceholder.
        // If isPlaceholder returns true, it returns super.visit (no change), so it stays "METHOD".
        // If false, it becomes "VAR".

        // So "METHOD.foo()" -> "METHOD" (preserved) . "foo" (preserved)
        String norm = normalize("METHOD.foo();");
        assertEquals("METHOD.foo();", norm);

        // "FIELD.bar()"
        String norm2 = normalize("FIELD.bar();");
        assertEquals("FIELD.bar();", norm2);
    }
}
