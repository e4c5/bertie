package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.raditha.dedup.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Tests for ExtractParentClassRefactorer.
 * Tests cross-file duplicate extraction to a common parent class.
 */
class ExtractParentClassRefactorerTest {

    private ExtractParentClassRefactorer refactorer;

    @BeforeEach
    void setUp() {
        refactorer = new ExtractParentClassRefactorer();
    }

    @Test
    void testBasicParentClassExtraction() {
        // Setup: Two service classes with identical method bodies
        String code1 = """
                package com.example;
                public class InventoryService {
                    public void processInventory() {
                        System.out.println("Start");
                        int x = 10;
                        int y = 20;
                        int sum = x + y;
                        System.out.println("Sum: " + sum);
                        System.out.println("End");
                    }
                }
                """;
        String code2 = """
                package com.example;
                public class ShippingService {
                    public void calculateShipping() {
                        System.out.println("Start");
                        int x = 10;
                        int y = 20;
                        int sum = x + y;
                        System.out.println("Sum: " + sum);
                        System.out.println("End");
                    }
                }
                """;

        CompilationUnit cu1 = StaticJavaParser.parse(code1);
        CompilationUnit cu2 = StaticJavaParser.parse(code2);

        DuplicateCluster cluster = createMultiFileMockCluster(cu1, cu2, 
                "processInventory", "calculateShipping");

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_PARENT_CLASS,
                "commonOperation",
                List.of(),
                "void",
                "BaseService",
                0.95,
                12);

        ExtractMethodRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        assertNotNull(result);
        assertEquals(3, result.modifiedFiles().size()); // Parent + 2 children
        
        // Check Parent Class was created
        String parentCode = result.modifiedFiles().values().stream()
                .filter(s -> s.contains("abstract class"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(parentCode, "Should contain abstract parent class");
        assertTrue(parentCode.contains("package com.example;"));
        assertTrue(parentCode.contains("abstract class BaseService"));
        assertTrue(parentCode.contains("protected void processInventory()"));
        assertTrue(parentCode.contains("System.out.println(\"Start\")"));
        
        // Check InventoryService extends parent
        String inventoryCode = result.modifiedFiles().values().stream()
                .filter(s -> s.contains("class InventoryService"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(inventoryCode, "Should contain InventoryService");
        assertTrue(inventoryCode.contains("extends BaseService"), 
                "InventoryService should extend BaseService");
        assertFalse(inventoryCode.contains("public void processInventory()"), 
                "Original method should be removed");
        
        // Check ShippingService extends parent
        String shippingCode = result.modifiedFiles().values().stream()
                .filter(s -> s.contains("class ShippingService"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(shippingCode, "Should contain ShippingService");
        assertTrue(shippingCode.contains("extends BaseService"), 
                "ShippingService should extend BaseService");
        assertFalse(shippingCode.contains("public void calculateShipping()"), 
                "Original method should be removed");
    }

    @Test
    void testParentClassNaming_CommonSuffix() {
        // Setup: Classes with common suffix "Service"
        String code1 = """
                package com.example;
                public class InventoryService {
                    public void process() { System.out.println("work"); }
                }
                """;
        String code2 = """
                package com.example;
                public class ShippingService {
                    public void process() { System.out.println("work"); }
                }
                """;

        CompilationUnit cu1 = StaticJavaParser.parse(code1);
        CompilationUnit cu2 = StaticJavaParser.parse(code2);

        DuplicateCluster cluster = createMultiFileMockCluster(cu1, cu2, "process", "process");

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_PARENT_CLASS,
                "process",
                List.of(),
                "void",
                "BaseService",
                0.95,
                5);

        ExtractMethodRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        // Parent should be named "BaseService" (common suffix)
        String parentCode = result.modifiedFiles().values().stream()
                .filter(s -> s.contains("abstract class"))
                .findFirst()
                .orElse(null);

        assertNotNull(parentCode);
        assertTrue(parentCode.contains("class BaseService"), 
                "Parent should be named BaseService from common suffix 'Service'");
    }

    @Test
    void testExtendsClauseAdded() {
        String code1 = """
                package com.example;
                public class ServiceA {
                    public void helper() { int x = 1; }
                }
                """;
        String code2 = """
                package com.example;
                public class ServiceB {
                    public void helper() { int x = 1; }
                }
                """;

        CompilationUnit cu1 = StaticJavaParser.parse(code1);
        CompilationUnit cu2 = StaticJavaParser.parse(code2);

        DuplicateCluster cluster = createMultiFileMockCluster(cu1, cu2, "helper", "helper");

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_PARENT_CLASS,
                "helper",
                List.of(),
                "void",
                "BaseService",
                0.95,
                5);

        ExtractMethodRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        // Parse the result to verify extends
        String serviceACode = result.modifiedFiles().values().stream()
                .filter(s -> s.contains("class ServiceA"))
                .findFirst()
                .orElseThrow();

        CompilationUnit modifiedCu = StaticJavaParser.parse(serviceACode);
        ClassOrInterfaceDeclaration classDecl = modifiedCu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        
        assertFalse(classDecl.getExtendedTypes().isEmpty(), "Should have extends clause");
        // When there's no common suffix > 3 chars, naming falls back to "Abstract" + first class name (ServiceA)
        assertEquals("AbstractServiceA", classDecl.getExtendedTypes().get(0).getNameAsString());
    }

    @Test
    void testMethodRemovedFromChildren() {
        String code1 = """
                package com.example;
                public class RepoA {
                    public String findData() {
                        System.out.println("Finding");
                        return "data";
                    }
                }
                """;
        String code2 = """
                package com.example;
                public class RepoB {
                    public String getData() {
                        System.out.println("Finding");
                        return "data";
                    }
                }
                """;

        CompilationUnit cu1 = StaticJavaParser.parse(code1);
        CompilationUnit cu2 = StaticJavaParser.parse(code2);

        DuplicateCluster cluster = createMultiFileMockCluster(cu1, cu2, "findData", "getData");

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_PARENT_CLASS,
                "findData",
                List.of(),
                "String",
                "AbstractRepo",
                0.95,
                5);

        ExtractMethodRefactorer.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        // Verify original methods are removed
        String repoACode = result.modifiedFiles().values().stream()
                .filter(s -> s.contains("class RepoA"))
                .findFirst()
                .orElseThrow();
        
        String repoBCode = result.modifiedFiles().values().stream()
                .filter(s -> s.contains("class RepoB"))
                .findFirst()
                .orElseThrow();
        
        assertFalse(repoACode.contains("public String findData()"), "findData should be removed");
        assertFalse(repoBCode.contains("public String getData()"), "getData should be removed");
    }

    private DuplicateCluster createMultiFileMockCluster(
            CompilationUnit cu1, CompilationUnit cu2, 
            String methodName1, String methodName2) {
        
        ClassOrInterfaceDeclaration clazz1 = cu1.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        MethodDeclaration method1 = clazz1.getMethods().stream()
                .filter(m -> m.getNameAsString().equals(methodName1))
                .findFirst()
                .orElseThrow();

        StatementSequence seq1 = new StatementSequence(
                method1.getBody().get().getStatements(),
                new Range(1, 1, 10, 1),
                0,
                method1,
                cu1,
                Paths.get("src/main/java/com/example/ServiceA.java"));

        ClassOrInterfaceDeclaration clazz2 = cu2.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        MethodDeclaration method2 = clazz2.getMethods().stream()
                .filter(m -> m.getNameAsString().equals(methodName2))
                .findFirst()
                .orElseThrow();

        StatementSequence seq2 = new StatementSequence(
                method2.getBody().get().getStatements(),
                new Range(1, 1, 10, 1),
                0,
                method2,
                cu2,
                Paths.get("src/main/java/com/example/ServiceB.java"));

        SimilarityResult sim = new SimilarityResult(1.0, 1.0, 1.0, 1.0, 10, 10, null, null, true);
        SimilarityPair pair = new SimilarityPair(seq1, seq2, sim);

        return new DuplicateCluster(seq1, List.of(pair), null, 100);
    }
}
