# Bertie - Duplicate Code Detector and Refactoring Tool

<div align="center">

**Automatically detect and refactor duplicate code using advanced similarity algorithms and intelligent refactoring strategies.**

[![Java 21](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.6+-blue)](https://maven.apache.org/)
[![Tests](https://img.shields.io/badge/Tests-166%2F180%20passing-yellow)](https://github.com/)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=e4c5_bertie&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=e4c5_bertie)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=e4c5_bertie&metric=coverage)](https://sonarcloud.io/summary/new_code?id=e4c5_bertie)

**Status**: Detection complete ✅ | Refactoring in beta ⚠️

</div>

---

## ⚠️ Important Notice

**Duplicate Detection**: Fully functional and production-ready  
**Refactoring**: Beta quality with known bugs (see [Known Issues](#known-issues))

**Recommended Use**:
- ✅ Use `analyze` command for duplicate detection
- ✅ Use `--mode dry-run` to preview refactorings
- ⚠️ Use `--mode interactive` with manual review
- ❌ Avoid `--mode batch` until P0 bugs are fixed

---

## Overview

Bertie is an intelligent duplication detector that automatically finds and refactors duplicate code in Java projects. It uses multi-algorithm similarity analysis combined with intelligent refactoring strategies to help you eliminate code duplication safely and efficiently.

### Key Features

- 🔍 **Smart Detection**: Multi-algorithm similarity analysis (LCS, Levenshtein, Structural)
- 🤖 **Intelligent Refactoring**: 4 automatic strategies (Extract Method, BeforeEach, ParameterizedTest, Utility Class)
- 🎯 **AI-Powered Naming**: Generates meaningful method names using Gemini AI
- 🛡️ **Safe Refactoring**: Automatic backups, compilation verification, rollback on failure
- 📊 **Metrics Export**: CSV and JSON export for dashboards and CI/CD integration
- ⚙️ **Flexible Modes**: Interactive, batch, and dry-run modes

---

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.6+
- Antikythera core library (automatically resolved via Maven)

### Installation

```bash
git clone <repository-url>
cd bertie
mvn clean install
```

### Basic Usage

1. **Configure your target** in `src/main/resources/generator.yml`:

```yaml
base_path: /path/to/your/project
duplication_detector:
  target_class: "com.example.YourClass"
  min_lines: 5
  threshold: 0.75
```

2. **Analyze duplicates**:

```bash
mvn exec:java -Dexec.args="analyze"
```

3. **Preview refactorings**:

```bash
mvn exec:java -Dexec.args="refactor --mode dry-run"
```

4. **Apply interactively**:

```bash
mvn exec:java -Dexec.args="refactor --mode interactive"
```

---

## Documentation

- [Quick Start Guide](docs/QUICK_START.md) - Get started in 5 minutes
- [Configuration Reference](docs/CONFIGURATION.md) - All configuration options  
- [Design Documentation](docs/duplication-detector/) - Technical design and architecture

---

## Command Reference

### Analyze Command

Detect duplicates without making changes:

```bash
mvn exec:java -Dexec.args="analyze [OPTIONS]"
```

**Options**:
- `--threshold N` - Similarity threshold 0-100 (default: 75)
- `--min-lines N` - Minimum duplicate size (default: 5)
- `--json` - Output in JSON format
- `--export FORMAT` - Export metrics (csv, json, or both)

### Refactor Command

Apply refactorings to eliminate duplicates:

```bash
mvn exec:java -Dexec.args="refactor [OPTIONS]"
```

**Modes**:
- `--mode interactive` - Review each refactoring (default)
- `--mode dry-run` - Preview without making changes
- `--mode batch` - Auto-apply high-confidence refactorings

**Verification**:
- `--verify compile` - Verify code compiles (default)
- `--verify test` - Run tests after refactoring
- `--verify none` - Skip verification

---

## Metrics Export

Export duplication metrics for dashboards and CI/CD pipelines:

```bash
# Export to CSV
mvn exec:java -Dexec.args="analyze --export csv"

# Export to JSON
mvn exec:java -Dexec.args="analyze --export json"

# Export both formats
mvn exec:java -Dexec.args="analyze --export both"
```

**Exported Metrics Include**:
- Project summary (total files, duplicates, clusters, LOC reduction)
- Per-file metrics (duplicates, similarity scores, refactoring strategies)
- ISO-8601 timestamps for historical tracking

---

## Refactoring Strategies

Bertie automatically selects the best strategy based on duplicate characteristics:

1. **Extract Helper Method** - Extracts duplicate code into reusable methods
2. **Extract to @BeforeEach** - Consolidates duplicate test setup code
3. **Extract to @ParameterizedTest** - Converts similar tests into parameterized tests
4. **Extract to Utility Class** - Moves stateless helpers to utility classes

---

## Configuration

### Basic Settings

```yaml
base_path: /path/to/project
duplication_detector:
  target_class: "com.example.service.UserService"
  min_lines: 5           # Minimum lines to consider duplicate
  threshold: 0.75        # 75% similarity threshold
```

### AI Service (Optional)

```yaml
ai_service:
  provider: "gemini"
  model: "gemini-2.0-flash-exp"
  api_key: "${GEMINI_API_KEY}"
  timeout_seconds: 30
```

Set environment variable:
```bash
export GEMINI_API_KEY="your-api-key-here"
```

---

## Dependencies

Bertie depends on:
- **Antikythera** (`sa.com.cloudsolutions:antikythera`) - Core library for Settings and AbstractCompiler
- **Antikythera Examples** (`antikythera-examples`) - For GeminiAIService
- **JavaParser** - Code analysis and AST manipulation
- **java-diff-utils** - Unified diff generation

---

## Project Structure

```
bertie/
├── src/main/java/com/raditha/dedup/
│   ├── analyzer/         # Duplication detection
│   ├── cli/              # Command-line interface
│   ├── clustering/       # Duplicate clustering
│   ├── config/           # Configuration management
│   ├── metrics/          # Metrics export
│   ├── refactoring/      # Refactoring engines
│   └── similarity/       # Similarity algorithms
├── src/test/java/        # Unit and integration tests
├── docs/                 # Documentation
└── pom.xml               # Maven configuration
```

---

## Known Issues

⚠️ **Refactoring features have known bugs** - Test status: 166/180 passing (92%)

### Current Limitations

**Safe Features**:
- ✅ Duplicate detection (`analyze` command)
- ✅ Dry-run preview (`--mode dry-run`)
- ✅ Metrics export (`--export csv/json`)

**Use with Caution**:
- ⚠️ Interactive refactoring - Manual review required
- ⚠️ Simple extractions usually work
- ⚠️ Complex refactorings may have edge cases

**Not Recommended**:
- ❌ Batch mode - Auto-apply disabled
- ❌ Production CI/CD without review

### Known Bugs

1. **Argument Extraction** - May use wrong values in some cases
2. **Return Value Detection** - Can select incorrect variable to return
3. **Type Inference** - Incomplete for complex expressions
4. **Literal Normalization** - String literal matching issues

**For Developers**: See [FUNCTIONAL_EQUIVALENCE_GAPS.md](docs/FUNCTIONAL_EQUIVALENCE_GAPS.md) for detailed gap analysis and [P0_GAP_FIXES_README.md](docs/P0_GAP_FIXES_README.md) for fix status.

---

## Development

### Build

```bash
mvn clean compile
```

### Run Tests

```bash
# Run all tests (14 failures expected)
mvn test

# Run only passing tests
mvn test -Dtest="!ReturnValueIntegrationTest,!VariationTrackerTest,!TokenNormalizerTest"
```

### Package

```bash
mvn package
```

---

## Contributing

Contributions are welcome! Areas needing help:
- **P0 Gap Fixes** - See [FUNCTIONAL_EQUIVALENCE_GAPS.md](docs/FUNCTIONAL_EQUIVALENCE_GAPS.md)
- **Test Coverage** - Fix failing tests
- **Documentation** - Improve user guides

Please ensure:
- All new tests pass (`mvn test`)
- Code follows existing style
- Documentation is updated
- SonarQube quality gate passes (see below)

### Code Quality

This project uses SonarCloud for continuous code quality monitoring:

- **Dashboard**: [View SonarCloud Analysis](https://sonarcloud.io/summary/new_code?id=e4c5_bertie)
- **Setup Guide**: See [docs/SONARQUBE_SETUP.md](docs/SONARQUBE_SETUP.md) for detailed setup instructions
- **Quick Reference**: See [docs/SONARQUBE_QUICK_REFERENCE.md](docs/SONARQUBE_QUICK_REFERENCE.md) for common issues and fixes

Quality standards:
- Maintain > 80% code coverage
- No blocker or critical issues
- Address security vulnerabilities immediately
- Keep cognitive complexity under 15


---

## License

[Add your license here]

---

## Support

For issues or questions, please open an issue on GitHub.

---

**Powered by Antikythera** | Built with ❤️ for code quality
