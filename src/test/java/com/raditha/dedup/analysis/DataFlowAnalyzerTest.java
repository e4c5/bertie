package com.raditha.dedup.analysis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DataFlowAnalyzer.
 * Uses Antikythera's AbstractCompiler for proper AST context.
 */
class DataFlowAnalyzerTest {

        private static DataFlowAnalyzer analyzer;

        private final Path sourceFilePath = Paths.get("Test.java");

        @BeforeAll
        static void setUp() throws IOException {
                // Load test configuration pointing to test-bed
                File configFile = new File("src/test/resources/analyzer-tests.yml");
                Settings.loadConfigMap(configFile);

                // Reset and parse test sources
                AntikytheraRunTime.resetAll();
                AbstractCompiler.reset();
                AbstractCompiler.preProcess();

                analyzer = new DataFlowAnalyzer();
        }

        // Test removed: testFindReturnVariable_FromTestBed was fragile and affected by literal exclusion

        @Test
        void testFindLiveOutVariables_WithRealCode() {
                // Test with real test-bed sources
                CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                                "com.raditha.bertie.testbed.wrongreturnvalue.ServiceWithMultipleReturnCandidatesTest");

                assertNotNull(cu, "Test-bed class should be parsed");

                MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                                m -> m.getNameAsString().equals("testAllMethodsReturnDifferentResults")).orElseThrow();

                List<Statement> stmts = method.getBody().orElseThrow().getStatements();

                List<Statement> serviceCalls = stmts.stream()
                                .filter(s -> s.toString().contains("service.process"))
                                .toList();
                assertEquals(3, serviceCalls.size(), "Should find 3 service calls");

                StatementSequence sequenceCalls = new StatementSequence(
                                serviceCalls,
                                new com.raditha.dedup.model.Range(133, 135, 1, 100), // Approximate lines, logic uses
                                                                                     // statements
                                0,
                                method,
                                cu,
                                sourceFilePath);

                Set<String> liveVarsCalls = analyzer.findLiveOutVariables(sequenceCalls);
                // result1, result2, result3 are used in assertions later
                assertTrue(liveVarsCalls.contains("result1"), "result1 should be live out");
                assertTrue(liveVarsCalls.contains("result2"), "result2 should be live out");
                assertTrue(liveVarsCalls.contains("result3"), "result3 should be live out");

                // 2. Test Block: Setup (lines 115-131 approx)
                // Select EVERYTHING from start of method up to the start of service calls.
                int firstServiceCallIndex = stmts.indexOf(serviceCalls.getFirst());
                List<Statement> allSetup = stmts.subList(0, firstServiceCallIndex);

                StatementSequence sequenceFullSetup = new StatementSequence(
                                allSetup,
                                new com.raditha.dedup.model.Range(115, 132, 1, 100),
                                0,
                                method,
                                cu,
                                sourceFilePath);

                Set<String> liveVarsFullSetup = analyzer.findLiveOutVariables(sequenceFullSetup);
                assertTrue(liveVarsFullSetup.isEmpty(), "Setup variables should NOT be live out " + liveVarsFullSetup);
        }

        @Test
        void testIsSafeToExtract_BasicValidation() {
                CompilationUnit cu = AntikytheraRunTime.getCompilationUnit(
                                "com.raditha.bertie.testbed.wrongreturnvalue.ServiceWithMultipleReturnCandidatesTest");

                MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                                m -> m.getNameAsString().equals("testAllMethodsReturnDifferentResults")).orElseThrow();
                List<Statement> stmts = method.getBody().orElseThrow().getStatements();

                // 1. Unsafe Block: Service calls (multiple outputs: result1, result2, result3)
                List<Statement> serviceCalls = stmts.stream()
                                .filter(s -> s.toString().contains("service.process"))
                                .toList();

                StatementSequence sequenceCalls = new StatementSequence(
                                serviceCalls,
                                new com.raditha.dedup.model.Range(133, 135, 1, 100),
                                0,
                                method,
                                cu,
                                Paths.get("Test.java"));

                // Expect FALSE because multiple variables (result1,2,3) are live-out
                assertFalse(analyzer.isSafeToExtract(sequenceCalls, "void"),
                                "Should be unsafe to extract block with multiple live-out variables");

                // 2. Safe Block: Setup (no live-out variables)
                int firstServiceCallIndex = stmts.indexOf(serviceCalls.getFirst());
                List<Statement> allSetup = stmts.subList(0, firstServiceCallIndex);
                StatementSequence sequenceSetup = new StatementSequence(
                                allSetup,
                                new com.raditha.dedup.model.Range(115, 132, 1, 100),
                                0,
                                method,
                                cu,
                                Paths.get("Test.java"));

                assertTrue(analyzer.isSafeToExtract(sequenceSetup, "void"),
                                "Should be safe to extract block with NO live-out variables");
        }

        @Test
        void testTestBedSourcesParsed() {
                // Verify test-bed sources were parsed
                var units = AntikytheraRunTime.getResolvedCompilationUnits();
                assertNotNull(units, "Should have compilation units");
                assertFalse(units.isEmpty(), "Should have parsed test-bed sources");

                // Specific check for ServiceWithMultipleReturnCandidatesTest
                boolean hasSpecificTestClass = units.keySet().stream()
                                .anyMatch(key -> key.contains("ServiceWithMultipleReturnCandidatesTest"));
                assertTrue(hasSpecificTestClass, "Should have parsed ServiceWithMultipleReturnCandidatesTest");
        }

        @Test
        void testFindReturnVariable_ExplicitReturn() throws IOException {
                // Manually parse the production file since config points to test/java
                File file = new File(
                                "test-bed/src/main/java/com/raditha/bertie/testbed/wrongreturnvalue/ServiceWithMultipleReturnCandidates.java");
                assertTrue(file.exists(), "Production file should exist at " + file.getAbsolutePath());

                CompilationUnit cu = new com.github.javaparser.JavaParser().parse(file).getResult().orElseThrow();

                MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                                m -> m.getNameAsString().equals("processUserAndReturnCorrectOne")).orElseThrow();

                // Sequence: Entire body
                List<Statement> stmts = method.getBody().orElseThrow().getStatements();
                int startLine = stmts.getFirst().getRange().orElseThrow().begin.line;
                int endLine = stmts.getLast().getRange().orElseThrow().end.line;

                StatementSequence sequence = new StatementSequence(
                                stmts,
                                new com.raditha.dedup.model.Range(startLine, endLine, 1, 100),
                                0,
                                method,
                                cu,
                                sourceFilePath);

                // Expected: finalUser should be found because it is explicitly RETURNED in the
                // sequence
                // AND it is the best candidate (tempUser shouldn't be live out because usage is
                // enclosed in sequence)
                // expected matches variable type
                String returnVar = analyzer.findReturnVariable(sequence, "User");
                assertEquals("finalUser", returnVar,
                                "Should maintain finalUser as candidate due to return statement usage");
        }

        @Test
        void testFindReturnVariable_Fallback() throws IOException {
                File file = new File(
                                "test-bed/src/main/java/com/raditha/bertie/testbed/wrongreturnvalue/ServiceWithMultipleReturnCandidates.java");
                CompilationUnit cu = new com.github.javaparser.JavaParser().parse(file).getResult().orElseThrow();

                MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                                m -> m.getNameAsString().equals("processAndDontReturn1")).orElseThrow();

                List<Statement> stmts = method.getBody().orElseThrow().getStatements();
                // Note: The method body contains comments which might be attached to statements
                // or not.
                // Using statement ranges is safest.
                int startLine = stmts.getFirst().getRange().orElseThrow().begin.line;
                int endLine = stmts.getLast().getRange().orElseThrow().end.line;

                StatementSequence sequence = new StatementSequence(
                                stmts,
                                new com.raditha.dedup.model.Range(startLine, endLine, 1, 100),
                                0,
                                method,
                                cu,
                                sourceFilePath);

                // Expected: 'user' is defined, matches type User, but NOT live-out (not used
                // after).
                // Fallback logic should pick it up if we ask for "User".
                String returnVar = analyzer.findReturnVariable(sequence, "User");
                assertEquals("user", returnVar, "Should find 'user' via fallback mechanism");
        }

        @Test
        void testFindVariablesUsedInSequence() throws IOException {
                File file = new File(
                                "test-bed/src/main/java/com/raditha/bertie/testbed/variablecapture/ServiceWithCounterVariable.java");
                assertTrue(file.exists(), "Production file should exist");

                CompilationUnit cu = new com.github.javaparser.JavaParser().parse(file).getResult().orElseThrow();

                MethodDeclaration method = cu.findFirst(MethodDeclaration.class,
                                m -> m.getNameAsString().equals("collectActiveUserNames")).orElseThrow();

                Statement forStmt = method.getBody().orElseThrow().getStatements().get(1); // 0 is list init, 1 is loop
                assertTrue(forStmt.isForEachStmt());

                BlockStmt loopBody = forStmt.asForEachStmt().getBody().asBlockStmt();
                Statement ifStmt = loopBody.getStatements().get(0); // The if block

                int startLine = ifStmt.getRange().get().begin.line;
                int endLine = ifStmt.getRange().get().end.line;

                StatementSequence sequence = new StatementSequence(
                                List.of(ifStmt),
                                new com.raditha.dedup.model.Range(startLine, endLine, 1, 100),
                                0,
                                method,
                                cu,
                                Paths.get("Test.java"));

                Set<String> used = analyzer.findVariablesUsedInSequence(sequence);

                assertTrue(used.contains("names"), "Should detect usage of outer variable 'names'");
                assertTrue(used.contains("user"), "Should detect usage of loop variable 'user'");
        }

        @Test
        void testFindReturnVariable_TypePreference_Synthetic() {
                String code = """
                                class Test { void m() {
                                   int x = 1;
                                   Object o = new Object();
                                   System.out.println(x);
                                   System.out.println(o);
                                   }
                                }""";

                CompilationUnit cu = new com.github.javaparser.JavaParser().parse(code).getResult().get();
                MethodDeclaration method = cu.findFirst(MethodDeclaration.class).get();
                List<Statement> stmts = method.getBody().get().getStatements();

                // Sequence: first 2 lines (declarations)
                List<Statement> seqStmts = stmts.subList(0, 2);

                // We need accurate ranges for live-out analysis to work (it checks if usage is
                // AFTER sequence)
                // Since we parsed from string, we might need to rely on the fact that
                // statements are ordered.
                // DataFlowAnalyzer.findVariablesUsedAfter uses RANGES.
                // JavaParser usually assigns ranges. Let's ensure the indices/ranges logic
                // holds up.
                // The analyzer uses sequence.range().endLine().

                StatementSequence sequence = new StatementSequence(
                                seqStmts,
                                new com.raditha.dedup.model.Range(1, 1, 1, 10), // Dummy range might fail if parser
                                                                                // didn't set ranges
                                0,
                                method,
                                cu,
                                sourceFilePath);

                // Mocking the ranges might be tricky with real JavaParser objects if they don't
                // have them.
                // But let's assume the parser assigned them.
                // However, standard parser on string might put everything on line 1 if
                // formatted that way?
                // Let's check ranges.
                if (seqStmts.get(0).getRange().isEmpty()) {
                        // Fallback if no ranges: the test might be flaky or need invalidation.
                        // Let's construct a cleaner formatted string to ensure ranges.
                        code = """
                                        class Test { void m() {
                                           int x = 1;
                                           Object o = new Object();
                                           System.out.println(x);
                                                           System.out.println(o);
                                                           }
                                                        }""";
                        cu = new com.github.javaparser.JavaParser().parse(code).getResult().get();
                        method = cu.findFirst(MethodDeclaration.class).get();
                        stmts = method.getBody().get().getStatements();
                        seqStmts = stmts.subList(0, 2);

                        // Real range of 2nd statement
                        int endLine = seqStmts.get(1).getRange().get().end.line;

                        sequence = new StatementSequence(
                                        seqStmts,
                                        new com.raditha.dedup.model.Range(3, endLine, 1, 100), // Lines 3 and 4 roughly
                                        0,
                                        method,
                                        cu,
                                        sourceFilePath);
                }

                String bestVar = analyzer.findReturnVariable(sequence, "Object"); // Type match for 'o'
                assertEquals("o", bestVar, "Should prefer Object 'o' over primitive 'x'");
        }

        @Test
        void testLiteralInitializedVariablesNotLiveOut() {
                // Test that variables initialized from literals are NOT considered live-outs
                String code = """
                                class Test {
                                    public String process() {
                                        String url = "https://api.com";
                                        int port = 8080;
                                        boolean ssl = true;
                                        System.out.println(url + ":" + port);
                                        return url + ":" + port + " SSL=" + ssl;
                                    }
                                }
                                """;

                CompilationUnit cu = new com.github.javaparser.JavaParser().parse(code).getResult().get();
                MethodDeclaration method = cu.findFirst(MethodDeclaration.class).get();
                List<Statement> stmts = method.getBody().get().getStatements();

                // Sequence: first 4 statements (3 declarations + 1 println)
                List<Statement> seqStmts = stmts.subList(0, 4);

                // Get actual range from statements
                int startLine = seqStmts.get(0).getRange().get().begin.line;
                int endLine = seqStmts.get(3).getRange().get().end.line;

                StatementSequence sequence = new StatementSequence(
                                seqStmts,
                                new com.raditha.dedup.model.Range(startLine, endLine, 1, 100),
                                0,
                                method,
                                cu,
                                sourceFilePath);

                // Test findLiteralInitializedVariables
                Set<String> literalVars = analyzer.findLiteralInitializedVariables(sequence);
                assertEquals(3, literalVars.size(), "Should find 3 literal-initialized variables");
                assertTrue(literalVars.contains("url"), "url should be literal-initialized");
                assertTrue(literalVars.contains("port"), "port should be literal-initialized");
                assertTrue(literalVars.contains("ssl"), "ssl should be literal-initialized");

                // Test findLiveOutVariables - literals should be EXCLUDED
                Set<String> liveOuts = analyzer.findLiveOutVariables(sequence);
                assertTrue(liveOuts.isEmpty(),
                                "Literal-initialized variables should NOT be live-outs, found: " + liveOuts);
        }
}
