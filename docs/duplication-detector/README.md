# Duplication Detector

A sophisticated Java code duplication detector with clustering, refactoring recommendations, and intelligent analysis.

**Status**: Core detection complete, refactoring is stable on test-bed (100% pass rate) but remains in beta for production code.

## Features

- **Multi-Algorithm Similarity Detection**: LCS + Levenshtein + Structural analysis
- **Intelligent Pre-Filtering**: 94%+ reduction in comparisons
- **Clustering**: Groups related duplicates for better insights
- **Refactoring Recommendations**: Suggests strategies (extract method, @BeforeEach, @ParameterizedTest, utility class)
- **Type-Safe Analysis**: Infers types and checks refactoring feasibility
- **CLI Interface**: Easy-to-use command-line tool with 3 modes
- **Multiple Output Formats**: Text, JSON, CSV metrics export
- **AI-Powered Naming**: Uses Gemini AI for semantic method names

## Quick Start

See [QUICK_START.md](../QUICK_START.md) for detailed setup instructions.

### Basic Commands

```bash
# Analyze duplicates (safe, read-only)
mvn exec:java -Dexec.args="analyze"

# Preview refactorings without making changes
mvn exec:java -Dexec.args="refactor --mode dry-run"

# Interactive refactoring with review
mvn exec:java -Dexec.args="refactor --mode interactive --verify compile"
```

## Architecture

See design documentation for details:
- [Design Document](duplication_detector_design.md) - Comprehensive technical design
- [Class Design](class_design.md) - Package structure and class relationships
- [Sequence Diagrams](sequence_diagrams.md) - Visual flow diagrams
- [Implementation Status](implementation_task_list.md) - Current progress

### Core Components

- **Phase 1-6**: Detection engine (extraction, normalization, similarity, filtering, clustering)
- **Phase 7**: Analysis components (type inference, scope analysis, variation tracking)
- **Phase 8**: Clustering & refactoring recommendations
- **Phase 9-10**: Refactoring engine + CLI

**Total**: ~40 classes, 180 tests (92% passing)

## Performance

- **Pre-filtering**: 94-100% reduction in comparisons
- **Sliding window**: Efficient sequence extraction  
- **Smart filtering**: Size, structural, and same-method filters
- **Caching**: Compilation units cached by Antikythera's AbstractCompiler

## Known Issues

⚠️ **Refactoring Status** - All refactoring tests in the `test-bed` are now passing (124/124).

**Safe to use**:
- Duplicate detection (`analyze` command)
- Dry-run mode (preview only)
- Metrics export

**Use with caution**:
- Interactive refactoring (review changes carefully)
- Simple single-method extractions

**Not recommended**:
- Batch mode (auto-apply)
- Production CI/CD integration

Current test status: 124/124 passing (100% coverage on test-bed)

## Configuration

See [CONFIGURATION.md](../CONFIGURATION.md) for all options.

Basic configuration in `src/main/resources/bertie.yml`:

```yaml
base_path: /path/to/your/project
duplication_detector:
  target_class: "com.example.YourClass"
  min_lines: 5        # Minimum lines to consider duplicate
  threshold: 0.75     # 75% similarity threshold
```

## Use Cases

1. **Code Review**: Find duplicates before merge (analyze command)
2. **Technical Debt**: Identify refactoring opportunities
3. **Metrics**: Export duplication metrics for dashboards
4. **Refactoring**: Semi-automated refactoring with manual review
3. **Technical Debt**: Measure and track duplication
4. **CI/CD Integration**: Fail builds with too much duplication

## License

Part of the Antikythera test generation project.

## Documentation

- [Complete Walkthrough](docs/duplication-detector/complete_walkthrough.md)
- [Design Document](docs/duplication-detector/duplication_detector_design.md)
- [Class Design](docs/duplication-detector/class_design.md)
- [Implementation Task List](docs/duplication-detector/implementation_task_list.md)
