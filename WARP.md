# WARP.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

## Project Overview

Bertie is an intelligent duplicate code detector and refactoring tool for Java projects. It analyzes Java codebases to find duplicate code using multi-algorithm similarity analysis and automatically refactors them using intelligent strategies.

**Core Capabilities:**
- Multi-algorithm similarity detection (LCS, Levenshtein, Structural)
- Intelligent pre-filtering (reduces comparisons by 94%+)
- Automatic duplicate clustering
- AI-powered method naming using Gemini
- Four refactoring strategies: Extract Method, BeforeEach, ParameterizedTest, Utility Class
- Safe refactoring with automatic backup and rollback
- Metrics export for CI/CD integration

**Technology Stack:**
- Java 21
- Maven 3.6+
- JavaParser 3.26.2 for AST manipulation
- Antikythera core library for Settings and AbstractCompiler
- Gemini AI for intelligent naming (optional)

## Common Commands

### Build and Test
```bash
# Clean build
mvn clean compile

# Run all tests
mvn test

# Run specific test
mvn test -Dtest=DuplicationAnalyzerTest

# Run tests for specific package
mvn test -Dtest="com.raditha.dedup.analyzer.*"

# Package (creates JAR)
mvn package

# Full clean install
mvn clean install
```

### Running the CLI

The main entry point is `com.raditha.dedup.cli.BertieCLI`.

```bash
# Analyze duplicates (read-only)
mvn exec:java -Dexec.args="analyze"

# Analyze with custom threshold
mvn exec:java -Dexec.args="analyze --threshold 80 --min-lines 3"

# Export metrics to CSV/JSON
mvn exec:java -Dexec.args="analyze --export csv"
mvn exec:java -Dexec.args="analyze --export json"
mvn exec:java -Dexec.args="analyze --export both"

# Preview refactorings (dry-run, safe)
mvn exec:java -Dexec.args="refactor --mode dry-run"

# Apply refactorings interactively
mvn exec:java -Dexec.args="refactor --mode interactive"

# Auto-apply high-confidence refactorings
mvn exec:java -Dexec.args="refactor --mode batch --verify compile"

# Refactor with test verification
mvn exec:java -Dexec.args="refactor --mode interactive --verify test"
```

### Configuration

The tool requires configuration in `src/main/resources/generator.yml`:

```yaml
base_path: /path/to/target/project
duplication_detector:
  target_class: "com.example.ClassName"
  min_lines: 5
  threshold: 0.75
```

For AI-powered naming, set the Gemini API key:
```bash
export GEMINI_API_KEY="your-api-key"
```

## Architecture Overview

### High-Level Data Flow

The system follows a **pipeline architecture** with distinct phases:

1. **Extraction** → Extract statement sequences from Java AST using JavaParser
2. **Normalization** → Convert statements to normalized tokens (done once, cached)
3. **Pre-filtering** → Fast structural filtering to eliminate unlikely pairs (94%+ reduction)
4. **Similarity Analysis** → Multi-algorithm comparison (LCS, Levenshtein, Structural)
5. **Clustering** → Group related duplicates by primary sequence
6. **Recommendation** → Select refactoring strategy based on context
7. **Refactoring** → Apply transformations with backup/rollback safety

### Core Components

#### 1. Detection Pipeline (com.raditha.dedup.analyzer)

**DuplicationAnalyzer**: Main orchestrator that coordinates the entire detection process
- Pre-normalizes all sequences once for performance
- Applies pre-filtering before expensive similarity calculations
- Clusters results and generates recommendations
- Returns DuplicationReport with all findings

**Key Design**: Pre-normalization optimization - tokens are computed once and cached in NormalizedSequence records, avoiding redundant normalization during pairwise comparisons.

#### 2. Extraction & Tokenization (com.raditha.dedup.extraction, detection)

**StatementExtractor**: Extracts statement sequences from JavaParser AST using sliding window
- Uses sliding window to find all possible sequence combinations
- Attaches context (containing method, compilation unit, source file)
- Respects minimum line count threshold

**TokenNormalizer**: Converts JavaParser statements to normalized tokens
- Normalizes identifiers (e.g., "userRepo.save(user)" → "METHOD_CALL(save)")
- Tracks original values for variation analysis
- Enables structural comparison while preserving semantics

**Key Design**: Uses JavaParser AST directly - no custom wrappers. StatementSequence holds direct references to MethodDeclaration and CompilationUnit from JavaParser.

#### 3. Similarity Analysis (com.raditha.dedup.similarity)

**SimilarityCalculator**: Combines three algorithms with configurable weights
- **LCSSimilarity**: Longest Common Subsequence - token-level matching
- **LevenshteinSimilarity**: Edit distance between token sequences
- **StructuralSimilarity**: AST structure pattern matching

**PreFilterChain**: Fast structural filters that eliminate unlikely pairs before expensive similarity calculation
- SizeFilter: Rejects sequences with vastly different lengths
- StructuralPreFilter: Checks token type distribution
- Same-method filter: Skips overlapping windows in same method

**Key Design**: Similarity weights are configurable (default: 0.4 LCS, 0.3 Levenshtein, 0.3 Structural).

#### 4. Analysis & Clustering (com.raditha.dedup.analysis, clustering)

**VariationTracker**: Identifies differences between similar sequences
- Tracks variations (literals, identifiers, method calls)
- Detects control flow differences (break refactorability)
- Used for parameter extraction in refactoring

**TypeAnalyzer**: Performs type compatibility analysis
- Infers types for variations using JavaParser's resolved types
- Checks if variations can be unified into method parameters
- Guards against unsafe refactorings

**DuplicateClusterer**: Groups duplicates by primary (earliest) sequence
- Groups all pairs sharing the same primary sequence
- Calculates LOC reduction potential per cluster
- Sorts clusters by refactoring value

**RefactoringRecommendationGenerator**: Selects optimal refactoring strategy
- Analyzes code context (test vs source, setup vs logic)
- Calculates confidence scores based on similarity and type safety
- Suggests method names (uses AI if configured)

#### 5. Refactoring Engine (com.raditha.dedup.refactoring)

**RefactoringEngine**: Orchestrates safe refactoring application
- Three modes: interactive, batch, dry-run
- Validates safety before applying changes
- Creates backups and rolls back on failure
- Verifies compilation (optionally tests) after changes

**Four Refactoring Strategies:**
1. **ExtractMethodRefactorer**: Generic helper method extraction
2. **ExtractBeforeEachRefactorer**: Test setup code → @BeforeEach
3. **ExtractParameterizedTestRefactorer**: Similar tests → @ParameterizedTest
4. **ExtractUtilityClassRefactorer**: Cross-class duplicates → utility class

**SafetyValidator**: Guards against unsafe refactorings
- Checks for overlapping duplicates
- Validates parameter count and complexity
- Ensures scope compatibility

**RefactoringVerifier**: Post-refactoring verification
- COMPILE: Verifies code compiles (default)
- TEST: Runs full test suite
- NONE: Skip verification

**Key Design**: Backup/rollback pattern - creates .backup files before modifications, rolls back on verification failure.

#### 6. Integration with Antikythera

**DuplicationDetectorSettings**: Loads configuration from generator.yml
- Uses Antikythera's Settings class for configuration
- Supports variable substitution (${HOME}, ${GEMINI_API_KEY})
- Provides configuration presets (strict, moderate, lenient)

**BertieCLI**: Main entry point
- Calls AbstractCompiler.preProcess() once to parse all source files
- Uses AntikytheraRunTime.getResolvedCompilationUnits() to get parsed AST
- Respects Antikythera's Settings.BASE_PATH and Settings.OUTPUT_PATH

**Key Design**: Leverages Antikythera infrastructure - doesn't re-parse files, uses existing Settings system, integrates with project's compilation context.

### Important Architectural Patterns

**1. Pre-Normalization Optimization**
All sequences are normalized once at the start, cached in NormalizedSequence records, and reused across all comparisons. This is critical for performance.

**2. Record-Based Data Model**
Heavy use of Java records for immutable data structures (StatementSequence, SimilarityResult, Token, etc.). No custom context wrappers - uses JavaParser classes directly.

**3. Pipeline with Pre-Filtering**
Expensive operations (similarity calculation) are deferred until after cheap filters eliminate most pairs. This achieves 94%+ reduction in comparisons.

**4. Variation Tracking During Normalization**
Tokens store both normalized and original values. During comparison, VariationTracker identifies differences that become method parameters.

**5. Safety-First Refactoring**
All refactorings follow: validate → backup → apply → verify → commit or rollback. No destructive operations without backups.

## Key Files and Their Roles

### Entry Points
- `BertieCLI.java` - Main CLI entry, argument parsing, command dispatch
- `DuplicationAnalyzer.java` - Core detection orchestrator
- `RefactoringEngine.java` - Refactoring orchestrator

### Configuration
- `src/main/resources/generator.yml` - Project configuration (must be edited for target project)
- `DuplicationDetectorSettings.java` - Configuration loader
- `DuplicationConfig.java` - Detection parameters (thresholds, weights)

### Models (Immutable Records)
- `StatementSequence.java` - Code sequence with AST references
- `Token.java` - Normalized token with original value
- `SimilarityResult.java` - Similarity scores + variation analysis
- `DuplicateCluster.java` - Group of related duplicates
- `RefactoringRecommendation.java` - Strategy + confidence + suggested name

### Critical Implementation Details

**Token Normalization**: The TokenNormalizer preserves both normalized values (for comparison) and original values (for variation tracking). This dual representation is fundamental to the system.

**Sliding Window**: StatementExtractor uses sliding window to generate all sequences of length >= minLines. Each window becomes a StatementSequence with full AST context.

**Type Resolution**: Uses JavaParser's type resolution when available, but gracefully degrades if types are unresolved. Type analysis is a best-effort optimization, not a requirement.

**Cluster Primary Selection**: The "primary" sequence in a cluster is the one with the lowest line number (appears first in file). All duplicates are grouped by their shared primary.

**Confidence Scoring**: Recommendations include confidence scores based on similarity threshold, type compatibility, and refactoring safety checks. Batch mode only applies high-confidence (≥90%) refactorings.

## Development Guidelines

### Running Tests
- All tests use JUnit 5
- Test files mirror main package structure
- Use Mockito for mocking (e.g., AI service in tests)
- Tests are self-contained and don't require external configuration

### Adding New Similarity Algorithms
1. Implement in `com.raditha.dedup.similarity`
2. Add weight to `SimilarityWeights.java`
3. Integrate into `SimilarityCalculator.calculate()`
4. Update tests and documentation

### Adding New Refactoring Strategies
1. Create refactorer in `com.raditha.dedup.refactoring`
2. Add strategy to `RefactoringStrategy` enum
3. Update `RefactoringRecommendationGenerator` selection logic
4. Add case to `RefactoringEngine.applyRefactoring()`
5. Create comprehensive tests

### Code Analysis Tips
- JavaParser AST navigation: Use `.findAncestor()`, `.findAll()`, `.getChildNodes()`
- Type resolution requires resolved CompilationUnits from AbstractCompiler
- Statement ranges come from `.getRange()` which returns Optional&lt;Range&gt;
- Method signatures: Use MethodDeclaration methods directly, don't parse strings

### Performance Considerations
- Pre-normalization is critical - don't normalize in loops
- Pre-filtering must be fast - avoid expensive operations
- Token comparison is O(n²) for sequence pairs, O(n⁴) for file - filtering reduces this drastically
- Clustering is O(n log n) with sorting, acceptable for typical duplicate counts

### Common Pitfalls
- **Don't re-normalize**: Tokens should be computed once and cached
- **Check Optional&lt;Range&gt;**: AST ranges can be absent for synthetic nodes
- **Handle unresolved types**: Type resolution can fail, code must degrade gracefully
- **Test with real code**: Simple synthetic tests miss edge cases - use real Java files
- **Backup before refactoring**: Always create backups, never modify files directly without safety net

## Testing Strategy

### Unit Tests
- Test each component in isolation
- Mock external dependencies (AI service, file I/O)
- Use JavaParser to construct test ASTs programmatically

### Integration Tests
- Test full pipeline with realistic code samples
- Verify end-to-end: extraction → detection → clustering → recommendation
- Check metrics export formats

### Refactoring Tests
- Verify generated code compiles
- Check that refactored code is functionally equivalent
- Test rollback on verification failure
- Test all refactoring strategies with edge cases

## Run the CLI
 - after each development cycle  do the following
   1. git reset --hard on the test-bed
   2. run the cli (com.raditha.dedup.cli.BertieCLI) with the following arguments refactor --mode batch --config-file src/main/resources/bertie.yml
   3. Make sure that the code in the test-bed is modified.
   4. validate that the test-bed code is compilable (check mvn compile exit status)
   5. reset the tests in the test-bed (test-bed/src/test/java folder)
   6. run the tests in the test-bed (mvn test)
   7. validate that the tests in the test-bed pass

 - if any of the above steps fail, then the refactoring is not successful and should be retried.

## Metrics and Observability

The tool exports metrics in CSV and JSON formats for CI/CD integration:

**Project Summary Metrics:**
- Total files analyzed
- Total duplicates found
- Total clusters identified
- Estimated LOC reduction
- Average similarity score
- Timestamp (ISO-8601)

**Per-File Metrics:**
- File name
- Duplicate count
- Cluster count
- Estimated LOC reduction per file
- Average similarity
- Recommended strategies

Files are timestamped and can be tracked over time to measure code quality trends.

