package com.raditha.dedup.refactoring;

import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.config.DuplicationConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefactoringParameterTypeTest {
    @TempDir
    Path tempDir;
    private DuplicationAnalyzer analyzer;
    private RefactoringEngine engine;

    @BeforeAll()
    static void setupClass() throws IOException {
        // Load test configuration pointing to test-bed
        File configFile = new File("src/test/resources/analyzer-tests.yml");
        if (configFile.exists()) {
             Settings.loadConfigMap(configFile);
        }
        
        AntikytheraRunTime.resetAll();
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();
    }

    @BeforeEach
    void setUp() {
        analyzer = new DuplicationAnalyzer(DuplicationConfig.lenient(), Collections.emptyMap());
    }

    @Test
    void testServiceWithTryCatchBlocks() throws IOException, InterruptedException {
        String code = """
package com.raditha.bertie.testbed.exceptionhandling;

public class ServiceWithTryCatchBlocks {
    /**
     * Process with NO exceptions at all.
     * This is pure computation, no I/O.
     */
    public int calculateSum(int[] numbers) {
        // Duplicate code block starts here
        int sum = 0;
        for (int num : numbers) {
            if (num > 0) {
                sum += num;
            }
        }
        int doubled = sum * 2;
        // Duplicate code block ends here
        return doubled;
    }

    /**
     * Calculate more with NO exceptions.
     */
    public int calculateProduct(int[] numbers) {
        // Duplicate code block starts here (similar to above)
        int sum = 0;
        for (int num : numbers) {
            if (num > 0) {
                sum += num;
            }
        }
        int doubled = sum * 2;
        // Duplicate code block ends here
        return doubled * 10; // Different final step
    }
}
""";
        Path sourceFile = tempDir.resolve("ServiceWithTryCatchBlocks.java");
        Files.writeString(sourceFile, code);
        
        CompilationUnit cu = com.github.javaparser.StaticJavaParser.parse(code);

        DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

        assertTrue(report.hasDuplicates(), "Should detect duplicates between calculateSum and calculateProduct");
        
        // Use INTERACTIVE mode to force application regardless of confidence
        // Simulate "y" input for confirmation
        System.setIn(new java.io.ByteArrayInputStream("y\\n".getBytes()));

        engine = new RefactoringEngine(
                tempDir,
                RefactoringEngine.RefactoringMode.INTERACTIVE,
                RefactoringVerifier.VerificationLevel.NONE);

        RefactoringEngine.RefactoringSession session = engine.refactorAll(report);
        
        // If this fails, it means Bertie refused to refactor
        assertFalse(session.getSuccessful().isEmpty(), "Refactoring should verify successfully");
        
        String refactoredCode = Files.readString(sourceFile);
        System.out.println("Refactored Code:\\n" + refactoredCode);
        
        assertTrue(refactoredCode.contains("private int extractedMethod1"), "Should contain helper method");
    }
}
