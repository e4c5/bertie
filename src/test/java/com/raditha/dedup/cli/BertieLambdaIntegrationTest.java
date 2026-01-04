package com.raditha.dedup.cli;

import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.config.DuplicationConfig;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BertieLambdaIntegrationTest {

    @Test
    void testLambdaHandling() throws IOException {
        // Setup
        Path sourceFile = Paths.get("test-bed/src/test/java/com/raditha/bertie/testbed/lambda/LambdaServiceTest.java");
        sa.com.cloudsolutions.antikythera.configuration.Settings
                .loadConfigMap(new java.io.File("src/test/resources/analyzer-tests.yml"));
        AntikytheraRunTime.resetAll();
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();

        CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(sourceFile.toString());
        if (cu == null) {
            // Fallback if not loaded by Antikythera preProcess (since it's a new file)
            cu = com.github.javaparser.StaticJavaParser.parse(sourceFile);
        }

        DuplicationAnalyzer analyzer = new DuplicationAnalyzer(DuplicationConfig.lenient());

        // Analysis - this should NOT throw exception
        DuplicationReport report = analyzer.analyzeFile(cu, sourceFile);

        // Assertions
        assertTrue(report.hasDuplicates(), "Should find duplicates");
    }
}
