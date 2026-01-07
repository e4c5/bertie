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

0. **Extraction** → Extract statement sequences from Java AST using JavaParser
101: 2. **Normalization** → Convert statements to normalized AST nodes (fuzzy matching)
102: 3. **Pre-filtering** → Fast structural filtering to eliminate unlikely pairs
103: 4. **Similarity Analysis** → Multi-algorithm comparison using AST-based metrics
104: 5. **Clustering** → Group related duplicates by primary sequence
105: 6. **Analysis** → Variation analysis on original AST to identify parameters
106: 7. **Refactoring** → Apply transformations with backup/rollback safety
107: 
108: ### System Architecture
109: 
110: ```mermaid
111: graph TD
112:     Source[Java Source] --> Parser[JavaParser]
113:     Parser --> AST[StatementSequences]
114:     AST --> Normalizer[ASTNormalizer]
115:     Normalizer --> NormNodes[NormalizedNodes]
116:     
117:     NormNodes --> Filter[StructuralPreFilter]
118:     Filter --> Similarity[SimilarityCalculator]
119:     Similarity --> Clusters[DuplicateClusterer]
120:     
121:     Clusters --> Analyzer[ASTVariationAnalyzer]
122:     AST --> Analyzer
123:     Analyzer --> Params[ASTParameterExtractor]
124:     
125:     Params --> Plan[ExtractionPlan]
126:     Plan --> Engine[RefactoringEngine]
127:     Engine --> Output[Refactored Code]
128:     
129:     subgraph Analysis Phase
130:     Analyzer
131:     Params
132:     end
133:     
134:     subgraph Detection Phase
135:     Normalizer
136:     Filter
137:     Similarity
138:     end
139: ```
140: 
141: ### Core Components

#### 1. Detection Pipeline (com.raditha.dedup.analyzer)

**DuplicationAnalyzer**: Main orchestrator that coordinates the entire detection process
- Normalizes sequences using `ASTNormalizer` for fuzzy comparison
- Applies pre-filtering (`StructuralPreFilter`) before expensive calculations
- Clusters results and generates recommendations
- Returns DuplicationReport with all findings

#### 2. Extraction & Normalization (com.raditha.dedup.extraction, normalization)

**StatementExtractor**: Extracts statement sequences from JavaParser AST using sliding window
- Uses sliding window to find all possible sequence combinations
- Attaches context (method, compilation unit, source file)

**ASTNormalizer**: Converts statements to `NormalizedNode` for fuzzy comparison
- Anonymizes identifiers ("user", "customer" → "VAR")
- Replaces literals with type placeholders ("Alice" → "STRING_LIT")
- Preserves AST structure for accurate similarity calculation
- Supports "Fuzzy" mode for structural pre-filtering

#### 3. Similarity Analysis (com.raditha.dedup.similarity)

**ASTSimilarityCalculator**: Combines algorithms on normalized AST nodes
- **ASTLCSSimilarity**: Longest Common Subsequence on normalized AST
- **ASTLevenshteinSimilarity**: Edit distance on normalized AST
- **ASTStructuralSimilarity**: Jaccard similarity of structural features

#### 4. Analysis & Clustering (com.raditha.dedup.analysis, clustering)

**ASTVariationAnalyzer**: Identifies semantic differences between duplicates
- Compares ORIGINAL ASTs to find varying expressions
- Identifies variable references and their scopes (Local, Field, Parameter)
- Resolves types using Antikythera's `Resolver` and `TypeWrapper`

**ASTParameterExtractor**: Generates extraction plans
- Converts varying expressions to parameters
- Converts variable references to arguments
- Handles type conversions and signature generation

**DuplicateClusterer**: Groups duplicates by primary sequence
- Groups pairs sharing the same primary sequence
- Calculates LOC reduction potential

#### 5. Refactoring Engine (com.raditha.dedup.refactoring)

**RefactoringEngine**: Orchestrates safe refactoring application
- Validates safety using `SafetyValidator`
- Creates backups and rolls back on failure
- Verifies compilation and tests

### Important Architectural Patterns

**1. Dual AST Representation**
Used `NormalizedNode` for detection (fuzzy, anonymized) and `StatementSequence` (original AST) for precise variation analysis and parameter extraction.

**2. Type-Safe Parameter Extraction**
Uses resolved AST types rather than string inference to ensure correct parameter types in extracted methods.

**3. Safety-First Refactoring**
All refactorings follow: validate → backup → extract → apply → verify.

## Key Files and Their Roles

### Entry Points
- `BertieCLI.java`
- `DuplicationAnalyzer.java`
- `RefactoringEngine.java`

### Models
- `StatementSequence.java` - Code sequence with AST references
- `NormalizedNode.java` - Normalized AST for comparison
- `VariationAnalysis.java` - Results of AST variation analysis
- `DuplicateCluster.java` - Group of related duplicates
- `RefactoringRecommendation.java` - Strategy + confidence

### Critical Implementation Details

**AST Normalization**: `ASTNormalizer` produced `NormalizedNode`s which are used ONLY for detection. Refactoring uses the original AST from `StatementSequence`.

**Type Resolution**: Relies heavily on Antikythera's `Resolver` to handle complex type inference (generics, inheritance) which is crucial for correct method signatures.
the required functionality.

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
- **Handle unresolved types**: Type resolution can fail, then exit early and try a better approach
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

## Exceptions
always fail fast.

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

