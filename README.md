# Bertie - Duplicate Code Detector and Refactoring Tool

**Automatically detect and refactor duplicate code using advanced similarity algorithms and intelligent refactoring strategies.**

[![Java 21](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.6+-blue)](https://maven.apache.org/)
[![Tests](https://img.shields.io/badge/Tests-100%25%20passing-green)](https://github.com/)

**Status**: Detection complete ✅ | Refactoring stable ✅

---

**Duplicate Detection**: Fully functional and production-ready  
**Refactoring**: Stable and verified with extensive integration tests

**Recommended Use**:
- ✅ Use `analyze` command for duplicate detection
- ✅ Use `--mode dry-run` to preview refactorings
- ✅ Use `--mode batch` for high-confidence refactorings
- ✅ Use `--mode interactive` for granular control

---

## Overview

Bertie is an intelligent duplication detector that automatically finds and refactors duplicate code in Java projects. It uses AST-based multi-algorithm similarity analysis combined with intelligent refactoring strategies to help you eliminate code duplication safely and efficiently.

### Key Features

- 🔍 **Smart Detection**: Multi-algorithm similarity analysis (AST-LCS, Levenshtein, Structural)
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

The primary interface is the `run-bertie.sh` script, which simplifies command execution.

1. **Configure your target** in `src/main/resources/bertie.yml`:

```yaml
base_path: /path/to/your/project
duplication_detector:
  target_class: "com.example.YourClass"
  min_lines: 5
  threshold: 0.75
```

2. **Analyze duplicates**:

```bash
./run-bertie.sh analyze
```

3. **Preview refactorings**:

```bash
./run-bertie.sh refactor --mode dry-run
```

4. **Apply refactorings**:

```bash
./run-bertie.sh refactor --mode batch --config-file src/main/resources/bertie.yml
```

---

## Command Reference

### Analyze Command

Detect duplicates without making changes:

```bash
./run-bertie.sh analyze [OPTIONS]
```

**Options**:
- `--threshold N` - Similarity threshold 0-100 (default: 75)
- `--min-lines N` - Minimum duplicate size (default: 5)
- `--json` - Output in JSON format
- `--export FORMAT` - Export metrics (csv, json, or both)

### Refactor Command

Apply refactorings to eliminate duplicates:

```bash
./run-bertie.sh refactor [OPTIONS]
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

## Configuration

### Basic Settings (`bertie.yml`)

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

## Known Issues

**Status**: 100% Tests Passing.

### Current Limitations

**Safe Features**:
- ✅ Duplicate detection (`analyze` command)
- ✅ Dry-run preview (`--mode dry-run`)
- ✅ Metrics export (`--export csv/json`)
- ✅ Refactoring strategies (`interactive`, `batch`)

**Safe for Production**:
- The tool has been verified with a full batch run on the test-bed.
- Automatic backups ensure safety during refactoring.

### Resolved Issues
1. **Argument Extraction** - Fixed return variable identification.
2. **Type Inference** - Added robust AST-based field resolution.
3. **Parameter Naming** - Implemented collision avoidance.

---

## Development

### Build

```bash
mvn clean compile
```

### Run Tests

```bash
mvn test
```

### Package

```bash
mvn package
```

---

## Support

For issues or questions, please open an issue on GitHub.

---

**Powered by Antikythera** | Built with ❤️ for code quality
