package com.raditha.dedup.analyzer;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.raditha.dedup.config.DuplicationDetectorSettings;
import com.raditha.dedup.model.SimilarityPair;
import com.raditha.dedup.model.StatementSequence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sa.com.cloudsolutions.antikythera.configuration.Settings;
import sa.com.cloudsolutions.antikythera.evaluator.AntikytheraRunTime;
import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that LSH candidate generation ({@code findCandidatesLSH}) does not lose
 * duplicates that the exhaustive O(N^2) path ({@code findCandidatesBruteForce}) finds.
 *
 * <p>The fixture plants groups of near-identical methods (renamed identifiers, different
 * literals) among structurally unrelated noise methods. Brute force above the configured
 * threshold is the ground truth; recall is the fraction of those pairs LSH also proposes.</p>
 *
 * <p>{@link #parameterSweep()} prints a recall / candidate-count table for a range of
 * {@code num_bands} x {@code rows_per_band} settings. It is a reporting harness and only
 * asserts that the default configuration keeps full recall on the planted duplicates.</p>
 */
class RecallVerificationTest {

    private static final int GROUPS = 12;
    private static final int COPIES_PER_GROUP = 3;
    private static final int NOISE_METHODS = 60;

    private static CompilationUnit cu;
    private static Path path;

    @BeforeAll
    static void setUpClass() throws IOException {
        Settings.loadConfigMap(new File("src/test/resources/analyzer-tests.yml"));
        AntikytheraRunTime.resetAll();
        AbstractCompiler.preProcess();
        cu = StaticJavaParser.parse(buildFixture());
        path = Paths.get("RecallFixture.java");
        cu.setStorage(path);
    }

    @AfterEach
    void resetSettings() {
        Settings.setProperty("duplication_detector_cli", new HashMap<String, Object>());
    }

    private static void configure(int numBands, int rowsPerBand) {
        Map<String, Object> cli = new HashMap<>();
        cli.put("num_bands", numBands);
        cli.put("rows_per_band", rowsPerBand);
        cli.put("min_lines", 4);
        Settings.setProperty("duplication_detector_cli", cli);
    }

    record Measurement(int numBands, int rowsPerBand, int truthPairs, int lshCandidates, int bruteCandidates,
            int recovered) {
        double recall() {
            return truthPairs == 0 ? 1.0 : (double) recovered / truthPairs;
        }
    }

    private Measurement measure(int numBands, int rowsPerBand) {
        configure(numBands, rowsPerBand);
        DuplicationAnalyzer analyzer = new DuplicationAnalyzer(Map.of("demo.RecallFixture", cu));
        List<StatementSequence> sequences = analyzer.extractSequences(cu, path);

        List<SimilarityPair> brute = analyzer.findCandidatesBruteForce(sequences);
        List<SimilarityPair> lsh = analyzer.findCandidatesLSH(sequences);

        double threshold = DuplicationDetectorSettings.getThreshold();
        Set<String> truth = new HashSet<>();
        for (SimilarityPair p : brute) {
            if (p.getScore() >= threshold) {
                truth.add(key(p));
            }
        }
        Set<String> lshAbove = new HashSet<>();
        for (SimilarityPair p : lsh) {
            if (p.getScore() >= threshold) {
                lshAbove.add(key(p));
            }
        }
        Set<String> recovered = new HashSet<>(truth);
        recovered.retainAll(lshAbove);
        return new Measurement(numBands, rowsPerBand, truth.size(), lsh.size(), brute.size(), recovered.size());
    }

    private static String key(SimilarityPair p) {
        String a = id(p.seq1());
        String b = id(p.seq2());
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private static String id(StatementSequence s) {
        return s.getContainingCallable().map(c -> c.getNameAsString()).orElse("?")
                + "@" + s.range().startLine() + "-" + s.range().endLine();
    }

    @Test
    void bruteForceFindsPlantedDuplicates() {
        Measurement m = measure(DuplicationDetectorSettings.getNumBands(),
                DuplicationDetectorSettings.getRowsPerBand());
        int plantedPairsLowerBound = GROUPS * (COPIES_PER_GROUP * (COPIES_PER_GROUP - 1) / 2);
        assertTrue(m.truthPairs() >= plantedPairsLowerBound,
                "brute force should see at least the planted pairs: " + m);
    }

    @Test
    void lshKeepsEveryBruteForceDuplicateWithDefaultSettings() {
        Measurement m = measure(DuplicationDetectorSettings.getNumBands(),
                DuplicationDetectorSettings.getRowsPerBand());

        assertEquals(m.truthPairs(), m.recovered(),
                "LSH lost duplicates that brute force finds: " + m);
        assertTrue(m.lshCandidates() <= m.bruteCandidates(),
                "LSH should never evaluate more pairs than brute force: " + m);
    }

    @Test
    void parameterSweep() {
        int defaultBands = DuplicationDetectorSettings.getNumBands();
        int defaultRows = DuplicationDetectorSettings.getRowsPerBand();
        int[][] configs = {
                {5, 4}, {10, 4}, {25, 4}, {50, 4},
                {10, 2}, {25, 2}, {50, 2},
                {20, 5}, {10, 10}, {5, 20},
        };
        List<Measurement> results = new ArrayList<>();
        StringBuilder table = new StringBuilder();
        table.append(String.format(Locale.ROOT, "%n%-9s %-12s %-10s %-10s %-14s %-16s%n",
                "numBands", "rowsPerBand", "truth", "recall", "lshCandidates", "bruteCandidates"));
        for (int[] cfg : configs) {
            Measurement m = measure(cfg[0], cfg[1]);
            results.add(m);
            table.append(String.format(Locale.ROOT, "%-9d %-12d %-10d %-10.3f %-14d %-16d%n",
                    m.numBands(), m.rowsPerBand(), m.truthPairs(), m.recall(), m.lshCandidates(),
                    m.bruteCandidates()));
        }
        System.out.println("LSH parameter sweep (recall vs brute-force baseline):" + table);

        Measurement defaults = results.stream()
                .filter(m -> m.numBands() == defaultBands && m.rowsPerBand() == defaultRows)
                .findFirst().orElseThrow();
        assertEquals(1.0, defaults.recall(), 0.0, "default LSH configuration must have full recall");

        // Sanity on the LSH probability curve: fewer bands / longer bands are stricter,
        // so candidate counts must not grow when rowsPerBand grows at fixed signature length.
        Measurement loose = results.stream().filter(m -> m.numBands() == 50 && m.rowsPerBand() == 2).findFirst().orElseThrow();
        Measurement strict = results.stream().filter(m -> m.numBands() == 10 && m.rowsPerBand() == 10).findFirst().orElseThrow();
        assertTrue(strict.lshCandidates() <= loose.lshCandidates(),
                "stricter banding should produce no more candidates: " + strict + " vs " + loose);
    }

    /**
     * Builds a class with {@value GROUPS} groups of {@value COPIES_PER_GROUP} near-identical
     * methods plus {@value NOISE_METHODS} distinct methods.
     */
    static String buildFixture() {
        StringBuilder sb = new StringBuilder();
        sb.append("package demo;\n");
        sb.append("import java.util.*;\n");
        sb.append("public class RecallFixture {\n");
        sb.append("    private final Map<String, Integer> stock = new HashMap<>();\n");
        sb.append("    private final List<String> audit = new ArrayList<>();\n");

        for (int g = 0; g < GROUPS; g++) {
            for (int c = 0; c < COPIES_PER_GROUP; c++) {
                sb.append(duplicateBody(g, c));
            }
        }
        for (int n = 0; n < NOISE_METHODS; n++) {
            sb.append(noiseBody(n));
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String duplicateBody(int group, int copy) {
        String v = "v" + group + "_" + copy;
        int lit = 10 + group * 7 + copy;
        return switch (group % 4) {
            case 0 -> """
                    public int calc%1$d_%2$d(String item, int qty) {
                        int %3$s = stock.getOrDefault(item, 0);
                        int remaining = %3$s - qty;
                        stock.put(item, remaining);
                        int total = qty * %4$d;
                        audit.add("calc " + item + total);
                        return total - remaining;
                    }
                    """.formatted(group, copy, v, lit);
            case 1 -> """
                    public String fmt%1$d_%2$d(List<String> parts) {
                        StringBuilder %3$s = new StringBuilder();
                        for (String p : parts) {
                            %3$s.append(p.trim()).append('%4$d');
                        }
                        audit.add(%3$s.toString());
                        return %3$s.toString().toUpperCase();
                    }
                    """.formatted(group, copy, v, lit % 10);
            case 2 -> """
                    public boolean check%1$d_%2$d(Map<String, Integer> m, String k) {
                        Integer %3$s = m.get(k);
                        if (%3$s == null) {
                            audit.add("missing " + k);
                            return false;
                        }
                        int adjusted = %3$s + %4$d;
                        return adjusted > %4$d * 2;
                    }
                    """.formatted(group, copy, v, lit);
            default -> """
                    public List<Integer> squares%1$d_%2$d(int n) {
                        List<Integer> %3$s = new ArrayList<>();
                        for (int i = 0; i < n; i++) {
                            %3$s.add(i * i + %4$d);
                        }
                        Collections.sort(%3$s);
                        audit.add("squares " + n);
                        return %3$s;
                    }
                    """.formatted(group, copy, v, lit);
        };
    }

    private static String noiseBody(int n) {
        return switch (n % 5) {
            case 0 -> """
                    public long noise%1$d(long seed) {
                        long x = seed ^ (seed << %2$d);
                        x ^= x >>> 7;
                        x *= 0x2545F4914F6CDD1DL;
                        return x + %1$d;
                    }
                    """.formatted(n, 3 + n % 11);
            case 1 -> """
                    public String noise%1$d(String s) {
                        if (s == null || s.isEmpty()) {
                            throw new IllegalArgumentException("empty %1$d");
                        }
                        char[] cs = s.toCharArray();
                        Arrays.sort(cs);
                        return new String(cs) + %1$d;
                    }
                    """.formatted(n);
            case 2 -> """
                    public double noise%1$d(double[] xs) {
                        double sum = 0;
                        double max = Double.NEGATIVE_INFINITY;
                        for (double x : xs) {
                            sum += x;
                            max = Math.max(max, x);
                        }
                        return xs.length == 0 ? %1$d : (sum / xs.length) - max;
                    }
                    """.formatted(n);
            case 3 -> """
                    public Map<Integer, String> noise%1$d(Set<String> words) {
                        Map<Integer, String> byLength = new TreeMap<>();
                        words.forEach(w -> byLength.merge(w.length() + %1$d, w, (a, b) -> a + "," + b));
                        while (byLength.size() > %1$d + 1) {
                            byLength.remove(byLength.keySet().iterator().next());
                        }
                        return byLength;
                    }
                    """.formatted(n);
            default -> """
                    public void noise%1$d(Iterator<String> it) {
                        int count = 0;
                        do {
                            if (!it.hasNext()) break;
                            String s = it.next();
                            count += s.length() %% %2$d;
                        } while (count < %1$d * 3);
                        audit.add(String.valueOf(count));
                    }
                    """.formatted(n, 2 + n % 5);
        };
    }
}
