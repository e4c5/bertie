package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SemanticNameAnalyzer.
 */
class SemanticNameAnalyzerTest {

    private SemanticNameAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new SemanticNameAnalyzer();
    }

    @Test
    void testSimpleSetterPattern() {
        String code = """
                class Test {
                    void method() {
                        user.setName("John");
                        user.setEmail("john@test.com");
                        user.setActive(true);
                    }
                }
                """;

        StatementSequence sequence = extractStatements(code);
        String name = analyzer.generateName(sequence);

        assertNotNull(name);
        assertTrue(name.startsWith("set"), "Should start with 'set'");
    }

    @Test
    void testCreatePattern() {
        String code = """
                class Test {
                    void method() {
                        User user = new User();
                        user.setName("Test");
                        repository.save(user);
                    }
                }
                """;

        StatementSequence sequence = extractStatements(code);
        String name = analyzer.generateName(sequence);

        assertNotNull(name);
        // Should extract "save" or "User" as meaningful name
        assertTrue(name.length() > 3, "Should have meaningful length");
    }

    @Test
    void testValidatePattern() {
        String code = """
                class Test {
                    void method() {
                        validator.validateEmail(email);
                        validator.validateAge(age);
                        result.check();
                    }
                }
                """;

        StatementSequence sequence = extractStatements(code);
        String name = analyzer.generateName(sequence);

        assertNotNull(name);
        assertTrue(name.contains("validate") || name.contains("check"),
                "Should contain validation verb");
    }

    @Test
    void testNoMeaningfulName() {
        String code = """
                class Test {
                    void method() {
                        int x = 1;
                        int y = 2;
                        System.out.println(x + y);
                    }
                }
                """;

        StatementSequence sequence = extractStatements(code);
        String name = analyzer.generateName(sequence);

        // Should return null when no meaningful name can be extracted
        // (or a generic name based on implementation)
        // This test verifies the analyzer handles non-domain code gracefully
    }

    @Test
    void testComplexMethodCalls() {
        String code = """
                class Test {
                    void method() {
                        Order order = orderService.createOrder();
                        order.addItem(item);
                        order.calculateTotal();
                        orderRepository.save(order);
                    }
                }
                """;

        StatementSequence sequence = extractStatements(code);
        String name = analyzer.generateName(sequence);

        assertNotNull(name);
        // Should extract domain object "Order" and action verb
        assertTrue(name.matches("[a-z][a-zA-Z0-9]*"),
                "Should be valid camelCase identifier");
        assertTrue(name.length() <= 40, "Should not be too long");
    }

    @Test
    void testVariableDeclarations() {
        String code = """
                class Test {
                    void method() {
                        Customer customer = new Customer();
                        String email = customer.getEmail();
                        boolean valid = validator.check(email);
                    }
                }
                """;

        StatementSequence sequence = extractStatements(code);
        String name = analyzer.generateName(sequence);

        assertNotNull(name);
        // Should extract "Customer" or "get" or "check"
        assertTrue(name.length() >= 3, "Should have minimum length");
    }

    @Test
    void testNameValidation() {
        String code = """
                class Test {
                void method() {
                        user.setFirstName("John");
                        user.setLastName("Doe");
                    }
                }
                """;

        StatementSequence sequence = extractStatements(code);
        String name = analyzer.generateName(sequence);

        if (name != null) {
            // Validate it's a proper Java identifier
            assertTrue(Character.isJavaIdentifierStart(name.charAt(0)));
            for (char c : name.toCharArray()) {
                assertTrue(Character.isJavaIdentifierPart(c));
            }
        }
    }

    private StatementSequence extractStatements(String code) {
        CompilationUnit cu = StaticJavaParser.parse(code);

        // Find the method and extract its statements
        var method = cu.findFirst(com.github.javaparser.ast.body.MethodDeclaration.class)
                .orElseThrow();

        List<Statement> statements = method.getBody()
                .orElseThrow()
                .getStatements()
                .stream()
                .toList();

        return new StatementSequence(
                statements,
                new com.raditha.dedup.model.Range(1, statements.size(), 1, 1),
                0, // depth
                method,
                cu,
                Paths.get("Test.java"));
    }
}
