package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.raditha.dedup.model.DuplicateCluster;
import com.raditha.dedup.model.RefactoringStrategy;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for MethodNameGenerator.
 */
class MethodNameGeneratorTest {

        private MethodNameGenerator generator;
        private ClassOrInterfaceDeclaration testClass;

        @BeforeEach
        void setUp() {
                generator = new MethodNameGenerator(false); // Disable AI for predictable tests

                // Create a test class with some existing methods
                String code = """
                                class TestClass {
                                    void existingMethod1() {}
                                    void existingMethod2() {}
                                    void load() {}
                                    void save() {}
                                }
                                """;
                CompilationUnit cu = StaticJavaParser.parse(code);
                testClass = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        }

        @Test
        void testSequentialNaming() {
                // Generate multiple sequential names
                String name1 = generator.generateName(
                                createMockCluster("int x = 1;"),
                                RefactoringStrategy.EXTRACT_HELPER_METHOD,
                                testClass,
                                MethodNameGenerator.NamingStrategy.SEQUENTIAL);

                String name2 = generator.generateName(
                                createMockCluster("int y = 2;"),
                                RefactoringStrategy.EXTRACT_HELPER_METHOD,
                                testClass,
                                MethodNameGenerator.NamingStrategy.SEQUENTIAL);

                // Should generate incrementing names
                assertTrue(name1.startsWith("extractedMethod"));
                assertTrue(name2.startsWith("extractedMethod"));
                assertNotEquals(name1, name2, "Sequential names should be different");
        }

        @Test
        void testSemanticNamingWithSetters() {
                String code = """
                                user.setName("John");
                                user.setEmail("john@test.com");
                                user.setActive(true);
                                """;

                String name = generator.generateName(
                                createMockCluster(code),
                                RefactoringStrategy.EXTRACT_HELPER_METHOD,
                                testClass,
                                MethodNameGenerator.NamingStrategy.SEMANTIC);

                assertNotNull(name);
                assertTrue(name.toLowerCase().contains("set"), "Should contain 'set' verb");
        }

        @Test
        void testSemanticNamingWithCreate() {
                String code = """
                                User user = new User();
                                user.setName("Test");
                                repository.save(user);
                                """;

                String name = generator.generateName(
                                createMockCluster(code),
                                RefactoringStrategy.EXTRACT_HELPER_METHOD,
                                testClass,
                                MethodNameGenerator.NamingStrategy.SEMANTIC);

                assertNotNull(name);
                // Should extract "save" or "User" from the code
                assertTrue(name.length() > 3, "Should have meaningful length");
        }

        @Test
        void testUniquenessGuarantee() {
                // Try to generate a name that conflicts with existing method
                String code = """
                                data.load();
                                data.process();
                                """;

                String name = generator.generateName(
                                createMockCluster(code),
                                RefactoringStrategy.EXTRACT_HELPER_METHOD,
                                testClass,
                                MethodNameGenerator.NamingStrategy.SEMANTIC);

                // testClass already has a method named "load"
                // Generator should make it unique
                assertNotEquals("load", name, "Should not use conflicting name");
                if (name.startsWith("load")) {
                        assertTrue(name.matches("load\\d+"), "Should append number: " + name);
                }
        }

        @Test
        void testFallbackChainSemanticToSequential() {
                // Code with no clear semantic meaning
                String code = """
                                int x = 1;
                                int y = 2;
                                int z = x + y;
                                """;

                String name = generator.generateName(
                                createMockCluster(code),
                                RefactoringStrategy.EXTRACT_HELPER_METHOD,
                                testClass,
                                MethodNameGenerator.NamingStrategy.SEMANTIC);

                // Should fall back to sequential when semantic fails
                assertNotNull(name);
                assertTrue(name.matches("extractedMethod\\d+"),
                                "Should fall back to sequential: " + name);
        }

        @Test
        void testPredefinedNamesForDifferentStrategies() {
                DuplicateCluster cluster = createMockCluster("int x = 1;");

                // BeforeEach should get "setUp" or variant
                String beforeEachName = generator.generateName(
                                cluster,
                                RefactoringStrategy.EXTRACT_TO_BEFORE_EACH,
                                testClass,
                                MethodNameGenerator.NamingStrategy.SEMANTIC);
                assertTrue(beforeEachName.toLowerCase().contains("setup") ||
                                beforeEachName.toLowerCase().contains("before"),
                                "BeforeEach should suggest setUp-like name: " + beforeEachName);

                // ParameterizedTest should get "test" or variant
                String testName = generator.generateName(
                                cluster,
                                RefactoringStrategy.EXTRACT_TO_PARAMETERIZED_TEST,
                                testClass,
                                MethodNameGenerator.NamingStrategy.SEMANTIC);
                assertTrue(testName.toLowerCase().contains("test"),
                                "ParameterizedTest should contain 'test': " + testName);
        }

        @Test
        void testMultipleGenerationsAreUnique() {
                String code = """
                                user.setName("test");
                                user.save();
                                """;

                DuplicateCluster cluster = createMockCluster(code);

                // Generate 5 names
                String[] names = new String[5];
                for (int i = 0; i < 5; i++) {
                        names[i] = generator.generateName(
                                        cluster,
                                        RefactoringStrategy.EXTRACT_HELPER_METHOD,
                                        testClass,
                                        MethodNameGenerator.NamingStrategy.SEQUENTIAL);
                }

                // All should be unique
                for (int i = 0; i < names.length; i++) {
                        for (int j = i + 1; j < names.length; j++) {
                                assertNotEquals(names[i], names[j],
                                                "Names should be unique: " + names[i] + " vs " + names[j]);
                        }
                }
        }

        @Test
        void testNoClassProvided() {
                // Should still work without a class for uniqueness checking
                String name = generator.generateName(
                                createMockCluster("int x = 1;"),
                                RefactoringStrategy.EXTRACT_HELPER_METHOD,
                                null, // No class
                                MethodNameGenerator.NamingStrategy.SEQUENTIAL);

                assertNotNull(name);
                assertTrue(name.startsWith("extractedMethod"));
        }

        @Test
        void testComplexSemanticPattern() {
                String code = """
                                Order order = orderService.createOrder();
                                order.addItem(item);
                                order.calculateTotal();
                                orderRepository.save(order);
                                """;

                String name = generator.generateName(
                                createMockCluster(code),
                                RefactoringStrategy.EXTRACT_HELPER_METHOD,
                                testClass,
                                MethodNameGenerator.NamingStrategy.SEMANTIC);

                assertNotNull(name);
                // Should extract domain object or action
                assertTrue(name.matches("[a-z][a-zA-Z0-9]*"),
                                "Should be valid camelCase: " + name);
                assertTrue(name.length() >= 3 && name.length() <= 40,
                                "Should have reasonable length: " + name);
        }

        private DuplicateCluster createMockCluster(String code) {
                // Create minimal mock cluster for testing
                CompilationUnit cu = StaticJavaParser.parse(
                                "class Test { void method() { " + code + " } }");

                var method = cu.findFirst(com.github.javaparser.ast.body.MethodDeclaration.class)
                                .orElseThrow();
                var statements = method.getBody().orElseThrow().getStatements();

                StatementSequence seq = new StatementSequence(
                                statements.stream().toList(),
                                new com.raditha.dedup.model.Range(1, statements.size(), 1, 1),
                                0,
                                method,
                                cu,
                                Paths.get("Test.java"));

                return new DuplicateCluster(
                                seq,
                                List.of(), // duplicates
                                null, // recommendation
                                1 // estimated LOC reduction
                );
        }
}
