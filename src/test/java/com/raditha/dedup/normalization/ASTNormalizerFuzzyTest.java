package com.raditha.dedup.normalization;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.stmt.Statement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ASTNormalizerFuzzyTest {

    private ASTNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new ASTNormalizer();
    }

    private Statement stmt(String code) { return StaticJavaParser.parseStatement(code); }

    @Test
    void normalize_vs_fuzzy_different_on_identifiers_only() {
        Statement s = stmt("user.getName();");

        var norm = normalizer.normalize(List.of(s));
        var fuzzy = normalizer.normalizeFuzzy(List.of(s));

        assertEquals("user.getName();", norm.get(0).normalized().toString(), "normalize (literals-only) should not change identifiers");
        // Method name "getName" should be preserved!
        assertEquals("VAR.getName();", fuzzy.get(0).normalized().toString(), "normalizeFuzzy should anonymize variables but preserve method names");
        assertNotEquals(norm.get(0).normalized().toString(), fuzzy.get(0).normalized().toString());
    }

    @Test
    void normalizeFuzzy_anonymizes_var_decl_and_field_access() {
        Statement s1 = stmt("int x = 5;");
        Statement s2 = stmt("this.value = other.field;");

        var fuzzy = normalizer.normalizeFuzzy(List.of(s1, s2));

        assertEquals("int VAR = INT_LIT;", fuzzy.get(0).normalized().toString());
        assertEquals("this.FIELD = VAR.FIELD;", fuzzy.get(1).normalized().toString());
    }

    @Test
    void both_modes_replace_literals_but_only_fuzzy_changes_identifiers() {
        Statement s = stmt("logger.info(\"hi\" + name);");

        var norm = normalizer.normalize(List.of(s));
        var fuzzy = normalizer.normalizeFuzzy(List.of(s));

        assertEquals("logger.info(STRING_LIT + name);", norm.get(0).normalized().toString());
        // "info" should be preserved
        assertEquals("VAR.info(STRING_LIT + VAR);", fuzzy.get(0).normalized().toString());
    }

    @Test
    void placeholders_in_input_are_preserved() {
        // If code already contains tokens like VAR or STRING_LIT as identifiers, they shouldn't be re-mapped
        Statement s = stmt("VAR.set(STRING_LIT);");
        var fuzzy = normalizer.normalizeFuzzy(List.of(s));
        // "set" should be preserved
        assertEquals("VAR.set(STRING_LIT);", fuzzy.get(0).normalized().toString());
    }
}
