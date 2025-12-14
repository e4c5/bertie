# Duplication Detector - Quick Start Guide

## Overview

The Duplication Detector automatically finds and refactors duplicate code in Java projects. It uses advanced similarity algorithms combined with intelligent refactoring strategies to help you eliminate code duplication.

---

## Installation

### Prerequisites
- Java 17+
- Maven 3.6+

### Setup
```bash
cd antikythera-examples
mvn clean install
```

---

## Basic Usage

### 1. Configure Your Target

Edit `src/main/resources/generator.yml`:

```yaml
base_path: /path/to/your/project

duplication_detector:
  target_class: "com.your.package.ClassName"
  min_lines: 5        # Minimum lines to consider duplicate
  threshold: 0.75     # 75% similarity threshold (0.0-1.0)
```

### 2. Analyze Duplicates

```bash
mvn exec:java -Dexec.mainClass="com.raditha.dedup.cli.DuplicationDetectorCLI" \
  -Dexec.args="analyze"
```

**Output**: Detailed report showing all duplicates with similarity scores and refactoring recommendations.

### 3. Preview Refactorings (Dry Run)

```bash
mvn exec:java -Dexec.mainClass="com.raditha.dedup.cli.DuplicationDetectorCLI" \
  -Dexec.args="refactor --mode dry-run"
```

**Output**: Shows exactly what changes would be made without actually modifying files.

### 4. Apply Refactorings (Interactive)

```bash
mvn exec:java -Dexec.mainClass="com.raditha.dedup.cli.DuplicationDetectorCLI" \
  -Dexec.args="refactor --mode interactive"
```

**Output**: Shows each refactoring and asks for your approval before applying.

---

## Command Reference

### Analyze Command

```bash
analyze [--threshold N] [--min-lines N]
```

Detects duplicates without making changes.

**Options**:
- `--threshold N`: Similarity threshold 0-100 (default: 75)
- `--min-lines N`: Minimum duplicate size (default: 5)
- `--json`: Output in JSON format

**Example**:
```bash
mvn exec:java -Dexec.mainClass="com.raditha.dedup.cli.DuplicationDetectorCLI" \
  -Dexec.args="analyze --threshold 80 --min-lines 3"
```

### Export Metrics

```bash
analyze --export FORMAT
```

Export duplication metrics to CSV or JSON for external analysis.

> [!IMPORTANT]
> Requires `generator.yml` configuration (see [Configuration File](#configuration-file) section).

**Formats**:
- `csv`: CSV format for Excel, spreadsheets, BI tools
- `json`: JSON format for APIs, dashboards, automated processing
- `both`: Generate both CSV and JSON files

**Examples**:
```bash
# Export to CSV
mvn exec:java -Dexec.mainClass="com.raditha.dedup.cli.DuplicationDetectorCLI" \
  -Dexec.args="analyze --export csv"

# Export to JSON
mvn exec:java -Dexec.mainClass="com.raditha.dedup.cli.DuplicationDetectorCLI" \
  -Dexec.args="analyze --export json"

# Export both formats
mvn exec:java -Dexec.mainClass="com.raditha.dedup.cli.DuplicationDetectorCLI" \
  -Dexec.args="analyze --export both --threshold 70"
```

**Output Files**:
- `duplication-metrics-YYYYMMDD-HHMMSS.csv`
- `duplication-metrics-YYYYMMDD-HHMMSS.json`

**Metrics Included**:

*Project Summary*:
- Total files analyzed
- Total duplicates found
- Total clusters identified
- Estimated LOC reduction
- Average similarity score
- Timestamp (ISO-8601 format)

*Per-File Metrics*:
- File name
- Duplicate count
- Cluster count
- Estimated LOC reduction
- Average similarity
- Recommended refactoring strategies

**Use Cases**:
- **CI/CD Integration**: Track duplication trends over time
- **Management Reports**: Generate executive summaries with Excel/Power BI
- **Dashboard Integration**: Feed JSON metrics to monitoring tools
- **Historical Analysis**: Compare metrics across releases

### Refactor Command

```bash
refactor --mode MODE [--verify LEVEL]
```

Applies refactorings to eliminate duplicates.

**Modes**:
- `interactive`: Review each refactoring (default)
- `dry-run`: Preview without making changes
- `batch`: Auto-apply high-confidence refactorings

**Verification Levels**:
- `compile`: Verify code compiles (default)
- `test`: Run tests after refactoring
- `none`: Skip verification

**Examples**:
```bash
# Preview all changes
refactor --mode dry-run

# Interactive review with test verification
refactor --mode interactive --verify test

# Auto-apply high-confidence only
refactor --mode batch --verify compile
```

---

## Refactoring Strategies

The tool automatically selects the best strategy based on the duplicate characteristics:

### 1. Extract Helper Method
Extracts duplicate code into a reusable method.

**Before**:
```java
public void method1() {
    if (config.isValid()) {
        config.process();
    }
}

public void method2() {
    if (config.isValid()) {
        config.process();
    }
}
```

**After**:
```java
private void validateAndProcess(Config config) {
    if (config.isValid()) {
        config.process();
    }
}

public void method1() {
    validateAndProcess(config);
}

public void method2() {
    validateAndProcess(config);
}
```

### 2. Extract to @BeforeEach
Consolidates duplicate test setup code.

**Best for**: JUnit test classes with repeated setup

### 3. Extract to @ParameterizedTest
Converts similar tests with different data into parameterized tests.

**Best for**: Multiple test methods testing the same logic with different inputs

### 4. Extract to Utility Class
Moves stateless helper methods to a dedicated utility class.

**Best for**: Static methods duplicated across multiple classes

---

## Safety Features

### Automatic Backups
Before any refactoring, the tool creates backups that can be restored if needed.

### Verification
After refactoring, the tool ensures:
- Code compiles successfully
- Optionally runs tests
- No syntax errors introduced

### Rollback
If verification fails, changes are automatically rolled back.

---

## Configuration Reference

See [CONFIGURATION.md](CONFIGURATION.md) for detailed configuration options.

---

## Troubleshooting

### "Target class not found"
- Ensure `base_path` points to your project root
- Use fully qualified class name: `com.package.ClassName`

### "No duplicates found"
- Lower the `threshold` (try 0.70 or 0.65)
- Reduce `min_lines` (try 3 or 4)

### "Validation failed"
- Check error message for specific issue
- Try `--mode dry-run` to preview the refactoring
- Some complex duplicates require manual review

---

## Next Steps

- Read [USER_GUIDE.md](USER_GUIDE.md) for advanced usage
- See [CONFIGURATION.md](CONFIGURATION.md) for all options
- Check examples in `/docs/examples/`

---

## Support

For issues or questions, see the main [README.md](../README.md)
