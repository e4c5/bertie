package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.raditha.dedup.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for field extraction to parent class.
 */
class FieldExtractionTest {

    private ParentClassExtractor refactorer;

    @BeforeEach
    void setUp() throws Exception {
        // Initialize Settings.props via reflection to avoid NPE
        Field propsField = Settings.class.getDeclaredField("props");
        propsField.setAccessible(true);
        if (propsField.get(null) == null) {
            propsField.set(null, new HashMap<String, Object>());
        }
        Settings.setProperty(Settings.BASE_PATH, ".");
        Settings.setProperty("jar_files", new ArrayList<String>());
        
        // Initialize AbstractCompiler.loader via reflection
        Field loaderField = AbstractCompiler.class.getDeclaredField("loader");
        loaderField.setAccessible(true);
        if (loaderField.get(null) == null) {
            loaderField.set(null, AbstractCompiler.class.getClassLoader());
        }
        
        refactorer = new ParentClassExtractor();
    }

    @Test
    void testFieldExtractionToParent() {
        // Setup: Two classes with duplicate fields and methods
        String code1 = """
                package com.example;
                public class UserService {
                    private String name;
                    private String email;
                    
                    public void process() {
                        System.out.println("Processing: " + name);
                        System.out.println("Email: " + email);
                    }
                }
                """;
        String code2 = """
                package com.example;
                public class CustomerService {
                    private String name;
                    private String email;
                    
                    public void process() {
                        System.out.println("Processing: " + name);
                        System.out.println("Email: " + email);
                    }
                }
                """;

        CompilationUnit cu1 = StaticJavaParser.parse(code1);
        CompilationUnit cu2 = StaticJavaParser.parse(code2);

        // Create cluster with field information
        DuplicateCluster cluster = createClusterWithFields(cu1, cu2);

        // Extract common fields for the recommendation
        ClassOrInterfaceDeclaration clazz1 = cu1.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        List<FieldDeclaration> commonFields = new ArrayList<>();
        for (FieldDeclaration field : clazz1.getFields()) {
            commonFields.add(field);
        }

        // Create VariationAnalysis with duplicated fields
        VariationAnalysis analysis = VariationAnalysis.builder()
                .duplicatedFields(commonFields)
                .build();

        RefactoringRecommendation recommendation = new RefactoringRecommendation(
                RefactoringStrategy.EXTRACT_PARENT_CLASS,
                "process",
                List.of(),
                StaticJavaParser.parseType("void"),
                "BaseService",
                0.95,
                10,
                null,
                -1,
                analysis);  // Pass the analysis with fields

        MethodExtractor.RefactoringResult result = refactorer.refactor(cluster, recommendation);

        assertNotNull(result);
        assertEquals(3, result.modifiedFiles().size()); // Parent + 2 children
        
        // Check Parent Class was created with fields
        String parentCode = result.modifiedFiles().values().stream()
                .filter(s -> s.contains("abstract class"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(parentCode, "Should contain abstract parent class");
        assertTrue(parentCode.contains("package com.example;"));
        assertTrue(parentCode.contains("abstract class BaseService"));
        assertTrue(parentCode.contains("protected String name;"), 
                "Parent should have name field with protected visibility");
        assertTrue(parentCode.contains("protected String email;"), 
                "Parent should have email field with protected visibility");
        assertTrue(parentCode.contains("public void process()"));
        
        // Check UserService extends parent and fields are removed
        String userCode = result.modifiedFiles().values().stream()
                .filter(s -> s.contains("class UserService"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(userCode, "Should contain UserService");
        assertTrue(userCode.contains("extends BaseService"), 
                "UserService should extend BaseService");
        assertFalse(userCode.contains("private String name;"), 
                "name field should be removed from UserService");
        assertFalse(userCode.contains("private String email;"), 
                "email field should be removed from UserService");
        
        // Check CustomerService extends parent and fields are removed
        String customerCode = result.modifiedFiles().values().stream()
                .filter(s -> s.contains("class CustomerService"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(customerCode, "Should contain CustomerService");
        assertTrue(customerCode.contains("extends BaseService"), 
                "CustomerService should extend BaseService");
        assertFalse(customerCode.contains("private String name;"), 
                "name field should be removed from CustomerService");
        assertFalse(customerCode.contains("private String email;"), 
                "email field should be removed from CustomerService");
    }

    private DuplicateCluster createClusterWithFields(CompilationUnit cu1, CompilationUnit cu2) {
        ClassOrInterfaceDeclaration clazz1 = cu1.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        MethodDeclaration method1 = clazz1.getMethods().get(0);

        StatementSequence seq1 = new StatementSequence(
                method1.getBody().get().getStatements(),
                new Range(1, 1, 10, 1),
                0,
                method1,
                cu1,
                Paths.get("src/main/java/com/example/UserService.java"));

        ClassOrInterfaceDeclaration clazz2 = cu2.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        MethodDeclaration method2 = clazz2.getMethods().get(0);

        StatementSequence seq2 = new StatementSequence(
                method2.getBody().get().getStatements(),
                new Range(1, 1, 10, 1),
                0,
                method2,
                cu2,
                Paths.get("src/main/java/com/example/CustomerService.java"));

        // Extract common fields
        List<FieldDeclaration> commonFields = new ArrayList<>();
        for (FieldDeclaration field : clazz1.getFields()) {
            commonFields.add(field);
        }

        // Create VariationAnalysis with duplicated fields
        VariationAnalysis analysis = VariationAnalysis.builder()
                .duplicatedFields(commonFields)
                .build();

        SimilarityResult sim = new SimilarityResult(1.0, 1.0, 1.0, 1.0, 10, 10, analysis, null, true);
        SimilarityPair pair = new SimilarityPair(seq1, seq2, sim);

        return new DuplicateCluster(seq1, List.of(pair), null, 100);
    }
}
