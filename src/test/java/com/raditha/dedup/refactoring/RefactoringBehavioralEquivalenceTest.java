package com.raditha.dedup.refactoring;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.raditha.dedup.analyzer.DuplicationAnalyzer;
import com.raditha.dedup.analyzer.DuplicationReport;
import com.raditha.dedup.cli.VerifyMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Before/after correctness tests: each fixture is compiled and executed both before and
 * after Bertie extracts the duplicated block, and the observable output must be identical.
 * <p>
 * The fixtures deliberately exercise the P0 correctness risks: per-occurrence argument
 * binding, string/numeric literal parameterisation and single-live-out return detection.
 */
class RefactoringBehavioralEquivalenceTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void setupClass() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/analyzer-tests.yml"));
    }

    @BeforeEach
    void setUp() throws IOException {
        AntikytheraRunTime.resetAll();
        AbstractCompiler.reset();
        AbstractCompiler.preProcess();

        Map<String, Object> cliConfig = new HashMap<>();
        cliConfig.put("maximal_only", false);
        cliConfig.put("min_lines", 3);
        cliConfig.put("threshold", 0.60);
        cliConfig.put("max_window_growth", 7);
        Settings.setProperty("duplication_detector_cli", cliConfig);
    }

    @AfterEach
    void tearDown() {
        Settings.setProperty("duplication_detector_cli", new HashMap<String, Object>());
    }

    /**
     * Literal-only variation: string and numeric literals differ between occurrences and
     * one variable (the computed total) is consumed after the block.
     */
    private static final String LITERALS = """
            package demo;

            public class Pricing {
                private final StringBuilder trace = new StringBuilder();

                public String priceA(int units) {
                    int base = units * 12;
                    int adjusted = base + 5;
                    String label = "alpha:" + adjusted;
                    trace.append(label).append(';');
                    return label;
                }

                public String priceB(int units) {
                    int base = units * 30;
                    int adjusted = base + 7;
                    String label = "beta:" + adjusted;
                    trace.append(label).append(';');
                    return label;
                }

                public String priceC(int units) {
                    int base = units * 4;
                    int adjusted = base + 1;
                    String label = "gamma:" + adjusted;
                    trace.append(label).append(';');
                    return label;
                }

                public static String run() {
                    Pricing p = new Pricing();
                    return p.priceA(3) + "|" + p.priceB(2) + "|" + p.priceC(9) + "|" + p.trace;
                }
            }
            """;

    /**
     * Variable/argument variation: each occurrence feeds different local variables and
     * fields into the duplicated block, in different positions.
     */
    private static final String ARGUMENTS = """
            package demo;

            public class Ledger {
                private final java.util.List<String> entries = new java.util.ArrayList<>();
                private final String prefix = "L";

                public int credit(int amount, int fee) {
                    int net = amount - fee;
                    String entry = prefix + net;
                    entries.add(entry);
                    int running = entries.size() * net;
                    return running;
                }

                public int debit(int amount, int fee) {
                    int net = fee - amount;
                    String entry = prefix + net;
                    entries.add(entry);
                    int running = entries.size() * net;
                    return running;
                }

                public int adjust(int amount, int fee) {
                    int net = amount * fee;
                    String entry = prefix + net;
                    entries.add(entry);
                    int running = entries.size() * net;
                    return running;
                }

                public static String run() {
                    Ledger l = new Ledger();
                    return l.credit(10, 2) + "," + l.debit(3, 9) + "," + l.adjust(4, 5) + "," + l.entries;
                }
            }
            """;

    /**
     * Two live-out variables: {@code limit} is literal-initialised (can be re-declared at
     * the call site) while {@code total} must be returned from the helper.
     */
    private static final String MULTI_LIVE_OUT = """
            package demo;

            public class Totals {
                private final java.util.List<String> log = new java.util.ArrayList<>();

                public int first(int x) {
                    int limit = 100;
                    java.util.List<Integer> vals = new java.util.ArrayList<>();
                    vals.add(x * 2);
                    vals.add(x + 3);
                    int total = vals.get(0) + vals.get(1);
                    log.add("first:" + limit);
                    return total - limit;
                }

                public int second(int x) {
                    int limit = 100;
                    java.util.List<Integer> vals = new java.util.ArrayList<>();
                    vals.add(x * 2);
                    vals.add(x + 3);
                    int total = vals.get(0) + vals.get(1);
                    log.add("second:" + total);
                    return total * limit;
                }

                public static String run() {
                    Totals t = new Totals();
                    return t.first(4) + "," + t.second(5) + "," + t.log;
                }
            }
            """;

    /**
     * Two non-literal live-out variables ({@code vals} and {@code total}); extraction must
     * either be refused or remain behaviour-preserving.
     */
    private static final String AMBIGUOUS_LIVE_OUT = """
            package demo;

            public class Ambiguous {
                public int first(int x) {
                    java.util.List<Integer> vals = new java.util.ArrayList<>();
                    vals.add(x * 2);
                    vals.add(x + 3);
                    int total = vals.get(0) + vals.get(1);
                    vals.add(total);
                    return vals.size() + total;
                }

                public int second(int x) {
                    java.util.List<Integer> vals = new java.util.ArrayList<>();
                    vals.add(x * 2);
                    vals.add(x + 3);
                    int total = vals.get(0) + vals.get(1);
                    vals.remove(0);
                    return vals.size() * total;
                }

                public static String run() {
                    Ambiguous a = new Ambiguous();
                    return a.first(4) + "," + a.second(5);
                }
            }
            """;

    @Test
    void literalLiveOutIsRedeclaredAndComplexLiveOutReturned() throws Exception {
        String refactored = assertEquivalentAfterRefactoring("demo.Totals", "Totals.java", MULTI_LIVE_OUT, true);
        CompilationUnit after = StaticJavaParser.parse(refactored);
        MethodDeclaration helper = after.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.isPrivate())
                .findFirst().orElseThrow();
        assertEquals("int", helper.getType().asString());
        assertTrue(helper.getBody().orElseThrow().toString().contains("return total;"),
                "complex live-out must be the return value:\n" + refactored);
        MethodDeclaration first = after.getClassByName("Totals").orElseThrow().getMethodsByName("first").getFirst();
        assertTrue(first.toString().contains("int limit = 100;"),
                "literal live-out must be re-declared at the call site:\n" + refactored);
    }

    @Test
    void ambiguousLiveOutsNeverBreakBehaviour() throws Exception {
        assertEquivalentAfterRefactoring("demo.Ambiguous", "Ambiguous.java", AMBIGUOUS_LIVE_OUT, false);
    }

    @Test
    void literalParametersPreserveBehaviour() throws Exception {
        assertEquivalentAfterRefactoring("demo.Pricing", "Pricing.java", LITERALS);
    }

    @Test
    void perOccurrenceArgumentsPreserveBehaviour() throws Exception {
        assertEquivalentAfterRefactoring("demo.Ledger", "Ledger.java", ARGUMENTS);
    }

    private String assertEquivalentAfterRefactoring(String fqn, String fileName, String source) throws Exception {
        return assertEquivalentAfterRefactoring(fqn, fileName, source, true);
    }

    private String assertEquivalentAfterRefactoring(String fqn, String fileName, String source,
            boolean expectExtraction) throws Exception {
        String before = compileAndRun(tempDir.resolve("before"), fileName, source, fqn);

        Path workDir = tempDir.resolve("after");
        Files.createDirectories(workDir);
        Path file = workDir.resolve(fileName);
        Files.writeString(file, source);

        CompilationUnit cu = StaticJavaParser.parse(source);
        cu.setStorage(file);
        DuplicationAnalyzer analyzer = new DuplicationAnalyzer(Map.of(fqn, cu));
        DuplicationReport report = analyzer.analyzeFile(cu, file);
        assertTrue(report.hasDuplicates(), "fixture must contain duplicates");

        RefactoringEngine engine = new RefactoringEngine(workDir,
                RefactoringEngine.RefactoringMode.BATCH, VerifyMode.NONE);
        RefactoringEngine.RefactoringSession session = engine.refactorAll(report);
        String refactored = Files.readString(file);
        if (expectExtraction) {
            assertFalse(session.getSuccessful().isEmpty(), "at least one extraction must be applied");
            assertEquals(0, session.getFailed().size(), "no failed refactorings");
            CompilationUnit after = StaticJavaParser.parse(refactored);
            long privateHelpers = after.findAll(MethodDeclaration.class).stream()
                    .filter(MethodDeclaration::isPrivate).count();
            assertTrue(privateHelpers > 0, "helper method should have been added:\n" + refactored);
        }

        String afterOutput = compileAndRun(tempDir.resolve("after-compiled"), fileName, refactored, fqn);
        assertEquals(before, afterOutput, "behaviour changed by refactoring:\n" + refactored);
        return refactored;
    }

    private static String compileAndRun(Path dir, String fileName, String source, String fqn) throws Exception {
        Path pkgDir = dir.resolve("demo");
        Files.createDirectories(pkgDir);
        Path file = pkgDir.resolve(fileName);
        Files.writeString(file, source);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null, "-d", dir.toString(), file.toString());
        assertEquals(0, rc, "compilation failed for:\n" + source);

        try (URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()}, null)) {
            Class<?> cls = loader.loadClass(fqn);
            Method run = cls.getMethod("run");
            return String.valueOf(run.invoke(null));
        }
    }
}
