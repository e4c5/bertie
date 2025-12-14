# Duplication Detector

A sophisticated Java code duplication detector with clustering, refactoring recommendations, and intelligent analysis.

## Features

- **Multi-Algorithm Similarity Detection**: LCS + Levenshtein + Structural analysis
- **Intelligent Pre-Filtering**: 94%+ reduction in comparisons
- **Clustering**: Groups related duplicates for better insights
- **Refactoring Recommendations**: Suggests strategies (extract method, @BeforeEach, @ParameterizedTest, utility class)
- **Type-Safe Analysis**: Infers types and checks refactoring feasibility
- **CLI Interface**: Easy-to-use command-line tool
- **Multiple Output Formats**: Text and JSON

## Quick Start

### Build
```bash
mvn clean install
```

### Run
```bash
# Analyze a single file
java -jar target/duplication-detector.jar MyClass.java

# Analyze a directory
java -jar target/duplication-detector.jar src/main/java

# Use strict preset (90% threshold, 7+ lines)
java -jar target/duplication-detector.jar --strict src/

# JSON output for tooling
java -jar target/duplication-detector.jar --json src/ > report.json
```

## CLI Options

```
Usage: java -jar duplication-detector.jar [options] <file-or-directory>

Options:
  --help, -h           Show help message
  --version, -v        Show version
  --min-lines N        Minimum lines for duplicate (default: 5)
  --threshold N        Similarity threshold 0-100 (default: 75)
  --strict             Use strict preset (90% threshold, 7 lines)
  --lenient            Use lenient preset (60% threshold, 3 lines)
  --json               Output as JSON

Examples:
  # Analyze with custom threshold
  java -jar duplication-detector.jar --threshold 85 src/main/java

  # Strict analysis with JSON output
  java -jar duplication-detector.jar --strict --json src/ > report.json
```

## Configuration Presets

- **Strict**: 90% similarity, 7+ lines (high confidence, fewer false positives)
- **Moderate** (default): 75% similarity, 5+ lines (balanced)
- **Lenient**: 60% similarity, 3+ lines (find more opportunities, more false positives)

## Example Output

```
================================================================================
DUPLICATION DETECTION SUMMARY
================================================================================

Files analyzed: 15
Total duplicates found: 8 in 3 clusters
Configuration: min-lines=5, threshold=75%

Clusters:
  #1: 3 occurrences, avg 92.5% similar, ~15 LOC reduction
      Strategy: EXTRACT_HELPER_METHOD (confidence: 95%)
  #2: 2 occurrences, avg 88.0% similar, ~8 LOC reduction
      Strategy: EXTRACT_TO_BEFORE_EACH (confidence: 85%)
```

## Architecture

- **Phase 1-6**: Core detection engine (27 classes)
- **Phase 7**: Analysis components (type inference, scope analysis)
- **Phase 8**: Clustering & recommendations
- **Phase 9**: CLI & reporting

Total: 33 classes, ~117 tests, all passing ✅

## Performance

- **Pre-filtering**: 94-100% reduction in comparisons
- **Sliding window**: Efficient sequence extraction  
- **Smart filtering**: Size, structural, and same-method filters

## Use Cases

1. **Code Review**: Find duplicates before merge
2. **Refactoring**: Identify improvement opportunities
3. **Technical Debt**: Measure and track duplication
4. **CI/CD Integration**: Fail builds with too much duplication

## License

Part of the Antikythera test generation project.

## Documentation

- [Complete Walkthrough](docs/duplication-detector/complete_walkthrough.md)
- [Design Document](docs/duplication-detector/duplication_detector_design.md)
- [Class Design](docs/duplication-detector/class_design.md)
- [Implementation Task List](docs/duplication-detector/implementation_task_list.md)
