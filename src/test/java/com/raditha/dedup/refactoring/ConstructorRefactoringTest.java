package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConstructorRefactoringTest {

    private MethodExtractor extractor;
    private Path sourcePath;

    @BeforeAll
    static void setupClass() throws IOException {
        File configFile = new File("src/test/resources/analyzer-tests.yml");
        if (configFile.exists()) {
            Settings.loadConfigMap(configFile);
        } else {
            // Fallback for isolated test runs if needed, or fail
            throw new IOException("Config file not found: " + configFile.getAbsolutePath());
        }
        AbstractCompiler.reset();
    }

    @BeforeEach
    void setUp() {
        extractor = new MethodExtractor();
        sourcePath = Paths.get("src/test/resources/TestClass.java");
    }

    @Test
    void testRefactorConstructorDuplicate() {
        String code = """
                class TestClass {
                    public TestClass() {
                        int a = 1;
                        int b = 2;
                        System.out.println(a + b);
                    }

                    public TestClass(int x) {
                        int a = 1;
                        int b = 2;
                        System.out.println(a + b);
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration classDecl = cu.getClassByName("TestClass").orElseThrow();
        ConstructorDeclaration ctor1 = classDecl.getConstructors().get(0);
        ConstructorDeclaration ctor2 = classDecl.getConstructors().get(1);

        // Extract sequences manually
        List<Statement> stmts1 = ctor1.getBody().getStatements();
        StatementSequence seq1 = new StatementSequence(
                stmts1,
                new Range(3, 25, 5, 34),
                0,
                ctor1,
                cu,
                sourcePath
        );

        List<Statement> stmts2 = ctor2.getBody().getStatements();
        StatementSequence seq2 = new StatementSequence(
                stmts2,
                new Range(9, 25, 11, 34),
                0,
                ctor2,
                cu,
                sourcePath
        );

        // Setup Cluster and Recommendation
        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1, seq2));
        when(cluster.duplicates()).thenReturn(List.of(new SimilarityPair(seq1, seq2, null)));
        when(cluster.getContainingMethods()).thenReturn(Collections.emptySet()); // Only constructors

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_HELPER_METHOD,
                "helperMethod",
                Collections.emptyList(),
                StaticJavaParser.parseType("void"),
                "TestClass",
                1.0,
                3,
                null
        );

        // Execute Refactoring
        MethodExtractor.RefactoringResult result = extractor.refactor(cluster, recommendation);

        // Verify Result
        assertNotNull(result);

        // Verify NO helper method was added (reused existing constructor)
        List<MethodDeclaration> methods = classDecl.getMethods();
        assertEquals(0, methods.size(), "Should have reused existing constructor instead of extracting helper");

        // Verify constructors: ctor1 is source, ctor2 calls ctor1
        // (Wait, ctor1 is the one with 0 params, so it's the reuse target)
        assertEquals(3, ctor1.getBody().getStatements().size()); // Unchanged

        assertEquals(1, ctor2.getBody().getStatements().size());
        assertTrue(ctor2.getBody().getStatements().get(0).toString().contains("this()"), 
                "Constructor 2 should reuse Constructor 1 via this()");
    }

    @Test
    void testRefactorWithExistingMethodCollision() {
        String code = """
                class TestClass {
                    public TestClass() {
                        int a = 1;
                        int b = 2;
                        System.out.println(a + b);
                    }

                    // Existing method with same signature as proposed helper
                    private void helperMethod() {
                        System.out.println("Different body");
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration classDecl = cu.getClassByName("TestClass").orElseThrow();
        ConstructorDeclaration ctor1 = classDecl.getConstructors().get(0);

        List<Statement> stmts1 = ctor1.getBody().getStatements();
        StatementSequence seq1 = new StatementSequence(
                stmts1,
                new Range(3, 25, 5, 34),
                0,
                ctor1,
                cu,
                sourcePath
        );

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seq1);
        when(cluster.allSequences()).thenReturn(List.of(seq1));
        when(cluster.duplicates()).thenReturn(Collections.emptyList()); // No duplicates for simplicity
        when(cluster.getContainingMethods()).thenReturn(Collections.emptySet());

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_HELPER_METHOD,
                "helperMethod",
                Collections.emptyList(),
                StaticJavaParser.parseType("void"),
                "TestClass",
                1.0,
                3,
                null
        );

        // Execute Refactoring
        // MethodExtractor logic:
        // 1. Create helperMethod (void, no params).
        // 2. findEquivalentHelper? No, different body.
        // 3. ensureHelperMethodAttached -> check exists? Yes (by name/sig).
        // 4. If exists, do NOT add member.
        // 5. proceed to replace code in ctor1 with call to helperMethod().

        MethodExtractor.RefactoringResult result = extractor.refactor(cluster, recommendation);

        assertNotNull(result);

        // Verify class methods count. Should be 2 (original + renamed new one).
        List<MethodDeclaration> methods = classDecl.getMethods();
        assertEquals(2, methods.size(), "Should verify method count is 2 (original + renamed new helper)");

        // Verify both methods exist
        assertTrue(methods.stream().anyMatch(m -> m.getNameAsString().equals("helperMethod")), "Original method should exist");
        assertTrue(methods.stream().anyMatch(m -> m.getNameAsString().equals("helperMethod1")), "New renamed method should exist");

        // Verify constructor calls the NEW renamed method
        assertTrue(ctor1.getBody().getStatements().get(0).toString().contains("helperMethod1()"),
                "Constructor should call the renamed helper method");
    }

    @Test
    void testRefactorConstructorDuplicate_RejectsUnmappableParameters() {
        // Setup: Master has a parameter 'unmappable' that doesn't exist in delegating ctor
        // and isn't assigned in the duplicate sequence.
        
        String code = """
                class TestClass {
                    public TestClass(int unmappable) {
                        int a = 1;
                    }
                    public TestClass() {
                        int a = 1;
                    }
                }
                """;

        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration classDecl = cu.getClassByName("TestClass").orElseThrow();
        ConstructorDeclaration master = classDecl.getConstructors().get(0);
        ConstructorDeclaration delegating = classDecl.getConstructors().get(1);

        StatementSequence seqMaster = new StatementSequence(
                master.getBody().getStatements(),
                new Range(1,1,1,1), 0, master, cu, sourcePath);
        
        StatementSequence seqDelegating = new StatementSequence(
                delegating.getBody().getStatements(),
                new Range(1,1,1,1), 0, delegating, cu, sourcePath);

        DuplicateCluster cluster = mock(DuplicateCluster.class);
        when(cluster.primary()).thenReturn(seqMaster);
        when(cluster.allSequences()).thenReturn(List.of(seqMaster, seqDelegating));
        when(cluster.duplicates()).thenReturn(List.of(new SimilarityPair(seqMaster, seqDelegating, null)));
        when(cluster.getContainingMethods()).thenReturn(Collections.emptySet());

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.CONSTRUCTOR_DELEGATION,
                null, Collections.emptyList(), null, "TestClass", 1.0, 1, null);

        // Fix: Use ConstructorExtractr directly as it handles CONSTRUCTOR_DELEGATION
        ConstructorExtractor refactorer = new ConstructorExtractor();
        refactorer.refactor(cluster, recommendation);

        // Verify: delegating constructor should NOT have been modified
        assertEquals(1, delegating.getBody().getStatements().size());
        assertFalse(delegating.getBody().getStatements().get(0).toString().contains("this("));
    }

    @Test
    void testSelectMasterConstructorPriority() {
        String code = """
                class TestClassPriority {
                    public TestClassPriority(int a) {
                        System.out.println("duplicate");
                    }
                    public TestClassPriority(int a, int b) {
                        System.out.println("duplicate");
                    }
                    public TestClassPriority(int a, int b) {
                        System.out.println("duplicate");
                    }
                }
                """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration clazz = cu.getClassByName("TestClassPriority").get();
        ConstructorDeclaration m1 = clazz.getConstructors().get(0); // 1 param
        ConstructorDeclaration m2 = clazz.getConstructors().get(1); // 2 params (MASTER)
        ConstructorDeclaration d = clazz.getConstructors().get(2);  // 2 params (DELEGATING)

        Path path = Path.of("TestClassPriority.java");
        StatementSequence s1 = new StatementSequence(m1.getBody().getStatements(), new com.raditha.dedup.model.Range(1, 1, 1, 1), 0, m1, cu, path);
        StatementSequence s2 = new StatementSequence(m2.getBody().getStatements(), new com.raditha.dedup.model.Range(4, 1, 4, 1), 0, m2, cu, path);
        StatementSequence sd = new StatementSequence(d.getBody().getStatements(), new com.raditha.dedup.model.Range(7, 1, 7, 1), 0, d, cu, path);

        // s2 is primary, m2 is master
        DuplicateCluster cluster = new DuplicateCluster(s2, List.of(new SimilarityPair(s2, s1, null), new SimilarityPair(s2, sd, null)), null, 0);
        
        ConstructorExtractor refactorer = new ConstructorExtractor();
        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.CONSTRUCTOR_DELEGATION,
                null, Collections.emptyList(), null, "TestClassPriority", 1.0, 1, null);
        
        refactorer.refactor(cluster, recommendation);

        // Verify: d (delegating) should delegate to either m2 or d (depending on max pick)
        // If m2 is chosen as master, then d should delegate to m2:
        if (d.getBody().getStatements().get(0).toString().contains("this(")) {
             assertEquals("this(a, b);", d.getBody().getStatements().get(0).toString().trim());
        } else {
             // If d was chosen as master, m2 should delegate to d:
             assertEquals("this(a, b);", m2.getBody().getStatements().get(0).toString().trim());
        }
    }

    @Test
    void testSelectMasterConstructorFallbackToPrimary() {
        CompilationUnit cu = new CompilationUnit();
        ClassOrInterfaceDeclaration clazz = cu.addClass("TestClass");

        // Constructor 1: Primary
        ConstructorDeclaration c1 = clazz.addConstructor();
        c1.getBody().addStatement("System.out.println(\"duplicate\");");

        // Constructor 2: Delegating
        ConstructorDeclaration c2 = clazz.addConstructor();
        c2.getBody().addStatement("System.out.println(\"duplicate\");");

        Path path = Path.of("TestClass.java");
        StatementSequence s1 = new StatementSequence(c1.getBody().getStatements(), new com.raditha.dedup.model.Range(1, 1, 1, 1), 0, c1, cu, path);
        StatementSequence s2 = new StatementSequence(c2.getBody().getStatements(), new com.raditha.dedup.model.Range(2, 1, 2, 1), 0, c2, cu, path);

        // s1 is primary, c1 is master. 
        // Note: s1.statements() should be the ENTIRE body for perfect master check.
        DuplicateCluster cluster = new DuplicateCluster(s1, List.of(new SimilarityPair(s1, s2, null)), null, 0);
        
        ConstructorExtractor refactorer = new ConstructorExtractor();
        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.CONSTRUCTOR_DELEGATION,
                null, Collections.emptyList(), null, "TestClass", 1.0, 1, null);
        
        refactorer.refactor(cluster, recommendation);

        // In this case, c1 IS a perfect master (duplicateCount == body.size == 1)
        // so c1 should be chosen as master, and c2 should delegate to it.
        assertEquals("this();", c2.getBody().getStatements().get(0).toString().trim());
    }

    @Test
    void testRefactorConstructorDuplicate_SkipsIfExplicitCall() {
        String code = """
                class TestClass {
                    public TestClass(int a) {
                        System.out.println("duplicate");
                    }
                    public TestClass() {
                        this(1); // Explicit call
                        System.out.println("duplicate");
                    }
                }
                """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration clazz = cu.getClassByName("TestClass").get();
        ConstructorDeclaration master = clazz.getConstructors().get(0);
        ConstructorDeclaration delegating = clazz.getConstructors().get(1);

        StatementSequence s1 = new StatementSequence(master.getBody().getStatements(), new com.raditha.dedup.model.Range(1,1,1,1), 0, master, cu, sourcePath);
        StatementSequence s2 = new StatementSequence(List.of(delegating.getBody().getStatement(1)), new com.raditha.dedup.model.Range(2,1,2,1), 0, delegating, cu, sourcePath);

        DuplicateCluster cluster = new DuplicateCluster(s1, List.of(new SimilarityPair(s1, s2, null)), null, 0);
        ConstructorExtractor refactorer = new ConstructorExtractor();
        refactorer.refactor(cluster, new RefactoringRecommendation(RefactoringStrategy.CONSTRUCTOR_DELEGATION, null, Collections.emptyList(), null, "TestClass", 1.0, 1, null));

        // Verify: delegating constructor should NOT have been modified
        assertEquals(2, delegating.getBody().getStatements().size());
        assertTrue(delegating.getBody().getStatement(0).toString().contains("this(1)"));
    }

    @Test
    void testRefactorConstructorDuplicate_SkipsIfNotAtStart() {
        String code = """
                class TestClass {
                    public TestClass() {
                        System.out.println("duplicate");
                    }
                    public TestClass(int a) {
                        System.out.println("not duplicate");
                        System.out.println("duplicate");
                    }
                }
                """;
        CompilationUnit cu = StaticJavaParser.parse(code);
        ClassOrInterfaceDeclaration clazz = cu.getClassByName("TestClass").get();
        ConstructorDeclaration master = clazz.getConstructors().get(0);
        ConstructorDeclaration delegating = clazz.getConstructors().get(1);

        StatementSequence s1 = new StatementSequence(master.getBody().getStatements(), new com.raditha.dedup.model.Range(1,1,1,1), 0, master, cu, sourcePath);
        // Duplicate is at index 1, so startOffset is 1
        StatementSequence s2 = new StatementSequence(List.of(delegating.getBody().getStatement(1)), new com.raditha.dedup.model.Range(2,1,2,1), 1, delegating, cu, sourcePath);

        DuplicateCluster cluster = new DuplicateCluster(s1, List.of(new SimilarityPair(s1, s2, null)), null, 0);
        ConstructorExtractor refactorer = new ConstructorExtractor();
        refactorer.refactor(cluster, new RefactoringRecommendation(RefactoringStrategy.CONSTRUCTOR_DELEGATION, null, Collections.emptyList(), null, "TestClass", 1.0, 1, null));

        // Verify: delegating constructor should NOT have been modified
        assertEquals(2, delegating.getBody().getStatements().size());
        assertTrue(delegating.getBody().getStatement(0).toString().contains("not duplicate"));
        assertTrue(delegating.getBody().getStatement(1).toString().contains("duplicate"));
    }
}
