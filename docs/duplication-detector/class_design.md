# Duplication Detector - Simplified Class Design (Using Antikythera + JavaParser)

**Version**: 2.0  
**Date**: December 9, 2025  
**Philosophy**: Leverage existing infrastructure, don't reinvent the wheel

---

## Key Simplifications

✅ **Use Antikythera's AbstractCompiler** - Already provides CompilationUnit parsing and caching  
✅ **Use Antikythera's Settings** - Already provides project configuration  
✅ **Use JavaParser's AST classes directly** - CompilationUnit, MethodDeclaration, etc.  
✅ **Eliminate custom Context wrappers** - FileContext, MethodContext, ScopeContext removed  

---

## Package Structure (Simplified)

```
com.raditha.dedup/
├── core/
│   ├── DuplicationAnalyzer.java          (Main entry point)
│   └── DuplicationReport.java            (Results container)
│
├── model/
│   ├── Token.java                        (Normalized token)
│   ├── StatementSequence.java            (Sequence with JavaParser refs)
│   ├── SimilarityResult.java             (Comparison result)
│   ├── VariationAnalysis.java            (Tracked differences)
│   ├── DuplicateCluster.java             (Group of duplicates)
│   └── RefactoringRecommendation.java    (Suggested fix)
│
├── detection/
│   ├── StatementExtractor.java           (Extract sequences from AST)
│   ├── TokenNormalizer.java              (Normalize + track variations)
│   ├── SimilarityCalculator.java         (LCS + Levenshtein + Structural)
│   └── PreFilterChain.java               (Size + Structural filters)
│
├── analysis/
│   ├── VariationTracker.java             (Find differences)
│   ├── TypeAnalyzer.java                 (Type compatibility)
│   ├── ScopeAnalyzer.java                (Extract scope info from AST)
│   ├── DuplicateClusterer.java           (Group similar sequences)
│   └── ParameterExtractor.java           (Infer method signatures)
│
├── refactoring/                          (Phase 2)
│   ├── RefactoringEngine.java
│   ├── ExtractMethodRefactorer.java
│   └── SafetyValidator.java
│
├── config/
│   └── DuplicationConfig.java            (Detection config)
│
├── report/
│   └── ReportGenerator.java              (Text + JSON)
│
└── cli/
    └── DuplicationDetectorCLI.java       (Main entry)
```

---

## Core Data Structures (Simplified)

### StatementSequence (No More Context Classes!)

```java
package com.raditha.dedup.model;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.Statement;
import java.nio.file.Path;
import java.util.List;

/**
 * Represents a sequence of statements that may be duplicate.
 * Uses JavaParser classes directly - no custom wrappers!
 */
public record StatementSequence(
    // The actual statements
    List<Statement> statements,
    Range range,
    int startOffset,
    
    // Direct references to JavaParser AST nodes
    MethodDeclaration containingMethod,    // From JavaParser
    CompilationUnit compilationUnit,        // From JavaParser (via AbstractCompiler)
    Path sourceFilePath                     // Just the path
) {
    /**
     * Get the class containing this sequence.
     */
    public ClassOrInterfaceDeclaration getContainingClass() {
        return containingMethod.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
    }
    
    /**
     * Get all imports from the file.
     */
    public List<String> getImports() {
        return compilationUnit.getImports().stream()
            .map(i -> i.getNameAsString())
            .toList();
    }
    
    /**
     * Get method name.
     */
    public String getMethodName() {
        return containingMethod.getNameAsString();
    }
    
    /**
     * Check if this is in a test class.
     */
    public boolean isInTestClass() {
        ClassOrInterfaceDeclaration clazz = getContainingClass();
        return clazz != null && 
               (clazz.getNameAsString().endsWith("Test") ||
                clazz.isAnnotationPresent("TestInstance"));
    }
}
```

### Token (Simplified)

```java
public record Token(
    TokenType type,
    String normalizedValue,     // METHOD_CALL(save)
    String originalValue,       // userRepo.save(user)
    String inferredType,        // void
    int lineNumber,
    int columnNumber
) {}

// No more astNodeId, fileContextId - we don't need them!
// We have direct references to CompilationUnit and MethodDeclaration
```

### Other Records (Unchanged)

```java
public record SimilarityResult(
    double overallScore,
    double lcsScore,
    double levenshteinScore,
    double structuralScore,
    VariationAnalysis variations,
    boolean typeCompatible,
    boolean canRefactor
) {}

public record VariationAnalysis(
    List<Variation> variations,
    boolean hasControlFlowDifferences
) {}

public record Variation(
    VariationType type,
    int alignedIndex1,
    int alignedIndex2,
    String value1,
    String value2,
    String inferredType
) {}

public record DuplicateCluster(
    StatementSequence primary,
    List<SimilarityPair> duplicates,
    RefactoringRecommendation recommendation,
    int estimatedLOCReduction
) {}
```

---

## Key Classes (Leveraging Antikythera)

### DuplicationAnalyzer (Main Entry Point)

```java
package com.raditha.dedup.core;

import sa.com.cloudsolutions.antikythera.parser.AbstractCompiler;
import sa.com.cloudsolutions.antikythera.configuration.Settings;

/**
 * Main orchestrator - uses AbstractCompiler for parsing.
 */
public class DuplicationAnalyzer {
    private final DuplicationConfig config;
    private final StatementExtractor extractor;
    private final SimilarityCalculator similarity;
    private final DuplicateClusterer clusterer;
    
    public DuplicationAnalyzer(DuplicationConfig config) {
        this.config = config;
        this.extractor = new StatementExtractor(config.minLines());
        this.similarity = new SimilarityCalculator(config.weights());
        this.clusterer = new DuplicateClusterer(config.threshold());
    }
    
    /**
     * Analyze a single file using AbstractCompiler.
     */
    public DuplicationReport analyzeFile(Path sourceFile) throws Exception {
        // Use Antikythera's AbstractCompiler for parsing
        AbstractCompiler compiler = new AbstractCompiler(sourceFile);
        CompilationUnit cu = compiler.getCompilationUnit();
        
        // Extract sequences
        List<StatementSequence> sequences = extractor.extract(cu, sourceFile);
        
        // Find duplicates
        List<DuplicateCluster> clusters = findDuplicates(sequences);
        
        return new DuplicationReport(clusters);
    }
    
    /**
     * Analyze entire project using Settings for project root.
     */
    public DuplicationReport analyzeProject() throws Exception {
        Settings settings = Settings.getInstance();
        Path basePath = Paths.get(settings.getBasePath());
        String basePackage = settings.getBasePackage();
        
        // Find all Java files
        List<Path> javaFiles = Files.walk(basePath)
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.toString().contains("/target/"))
            .filter(p -> !p.toString().contains("/generated/"))
            .toList();
        
        List<DuplicateCluster> allClusters = new ArrayList<>();
        
        // Analyze each file
        for (Path file : javaFiles) {
            try {
                DuplicationReport report = analyzeFile(file);
                allClusters.addAll(report.clusters());
            } catch (Exception e) {
                System.err.println("Error analyzing " + file + ": " + e.getMessage());
            }
        }
        
        // Cluster across files
        return new DuplicationReport(clusterer.clusterAll(allClusters));
    }
    
    private List<DuplicateCluster> findDuplicates(List<StatementSequence> sequences) {
        // Pre-filter pairs
        List<SequencePair> candidates = generateCandidates(sequences);
        
        // Calculate similarities (parallel)
        List<SimilarityResult> results = candidates.parallelStream()
            .map(pair -> similarity.compare(pair.seq1(), pair.seq2()))
            .filter(r -> r.overallScore() > config.threshold())
            .toList();
        
        // Cluster
        return clusterer.cluster(results);
    }
}
```

### StatementExtractor (Using JavaParser Directly)

```java
package com.raditha.dedup.detection;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

/**
 * Extracts statement sequences using JavaParser's AST visitor.
 */
public class StatementExtractor {
    private final int minLines;
    
    public StatementExtractor(int minLines) {
        this.minLines = minLines;
    }
    
    /**
     * Extract all sequences from a CompilationUnit.
     */
    public List<StatementSequence> extract(CompilationUnit cu, Path filePath) {
        List<StatementSequence> sequences = new ArrayList<>();
        
        // Visit all methods using JavaParser's visitor
        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodDeclaration method, Void arg) {
                sequences.addAll(extractFromMethod(method, cu, filePath));
                super.visit(method, arg);
            }
        }, null);
        
        return sequences;
    }
    
    private List<StatementSequence> extractFromMethod(
        MethodDeclaration method, 
        CompilationUnit cu,
        Path filePath
    ) {
        BlockStmt body = method.getBody().orElse(null);
        if (body == null) return List.of();
        
        List<Statement> statements = body.getStatements();
        List<StatementSequence> sequences = new ArrayList<>();
        
        // Sliding window
        for (int i = 0; i <= statements.size() - minLines; i++) {
            for (int windowSize = minLines; i + windowSize <= statements.size(); windowSize++) {
                List<Statement> window = statements.subList(i, i + windowSize);
                
                StatementSequence seq = new StatementSequence(
                    window,
                    Range.range(window.get(0).getRange().get(), 
                               window.get(windowSize-1).getRange().get()),
                    i,
                    method,      // Direct reference - no wrapper!
                    cu,          // Direct reference - no wrapper!
                    filePath
                );
                
                sequences.add(seq);
            }
        }
        
        return sequences;
    }
}
```

### ScopeAnalyzer (Extracting Info from JavaParser AST)

```java
package com.raditha.dedup.analysis;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;

/**
 * Analyzes scope by walking JavaParser AST - no custom ScopeContext needed!
 */
public class ScopeAnalyzer {
    
    /**
     * Get all variables available at a given statement.
     */
    public List<VariableInfo> getAvailableVariables(StatementSequence seq) {
        List<VariableInfo> variables = new ArrayList<>();
        
        // Method parameters
        seq.containingMethod().getParameters().forEach(param -> {
            variables.add(new VariableInfo(
                param.getNameAsString(),
                param.getTypeAsString(),
                true,  // isParameter
                false, // isField
                param.isFinal()
            ));
        });
        
        // Local variables declared before this sequence
        // (walk AST up to startOffset)
        seq.containingMethod().getBody().ifPresent(body -> {
            body.getStatements().stream()
                .limit(seq.startOffset())
                .forEach(stmt -> {
                    stmt.findAll(VariableDeclarator.class).forEach(var -> {
                        variables.add(new VariableInfo(
                            var.getNameAsString(),
                            var.getTypeAsString(),
                            false,
                            false,
                            false
                        ));
                    });
                });
        });
        
        // Class fields
        ClassOrInterfaceDeclaration clazz = seq.getContainingClass();
        if (clazz != null) {
            clazz.getFields().forEach(field -> {
                field.getVariables().forEach(var -> {
                    variables.add(new VariableInfo(
                        var.getNameAsString(),
                        var.getTypeAsString(),
                        false,
                        true,  // isField
                        field.isFinal()
                    ));
                });
            });
        }
        
        return variables;
    }
    
    public record VariableInfo(
        String name,
        String type,
        boolean isParameter,
        boolean isField,
        boolean isFinal
    ) {}
}
```

---

## Configuration (Using Settings)

```java
package com.raditha.dedup.config;

/**
 * Detection configuration - much simpler now.
 */
public record DuplicationConfig(
    int minLines,
    double threshold,
    SimilarityWeights weights,
    boolean includeTests,
    List<String> excludePatterns
) {
    public static DuplicationConfig moderate() {
        return new DuplicationConfig(
            4, 
            0.75, 
            new SimilarityWeights(0.40, 0.40, 0.20),
            true,
            List.of("**/target/**", "**/generated/**")
        );
    }
}

public record SimilarityWeights(
    double lcsWeight,
    double levenshteinWeight,
    double structuralWeight
) {}
```

---

## Memory Management (Simplified)

**Problem**: Multiple sequences from same file sharing CompilationUnit

**Solution**: Natural sharing via references
- JavaParser's CompilationUnit is immutable
- All StatementSequence records from same file reference THE SAME CompilationUnit object
- Java's garbage collector handles deduplication automatically
- No need for custom ContextManager!

**Memory Impact**:
```
Before (with custom contexts):
- 100 sequences × 1KB each = 100KB (+ complex manager)

After (with direct references):
- 100 sequences × 200 bytes each = 20KB
- 1 CompilationUnit (shared) = 50KB
- Total: 70KB (no manager needed!)
```

---

## Summary

**Eliminated**:
- ❌ FileContext
- ❌ MethodContext  
- ❌ ScopeContext
- ❌ ContextManager
- ❌ All lightweight ID mappings

**Now Using**:
- ✅ AbstractCompiler (from Antikythera) - parsing + caching
- ✅ Settings (from Antikythera) - project configuration
- ✅ CompilationUnit (from JavaParser) - AST + imports
- ✅ MethodDeclaration (from JavaParser) - method AST
- ✅ ClassOrInterfaceDeclaration (from JavaParser) - class info
- ✅ Natural Java object references - automatic sharing

**Result**: 
- ~40% fewer custom classes
- Leverages battle-tested infrastructure
- Simpler, more maintainable code
- Natural memory sharing without complex management

**Philosophy**: Use what exists, build only what's unique (detection algorithms, refactoring logic).
