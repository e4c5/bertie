# Bertie Documentation

Welcome to the Bertie documentation. This directory contains comprehensive guides for using and developing Bertie.

---

## Getting Started

Start here if you're new to Bertie:

- **[Quick Start Guide](QUICK_START.md)** - Get up and running in 5 minutes
- **[Configuration Reference](CONFIGURATION.md)** - All configuration options
- **[Batch Mode Guide](BATCH_MODE.md)** - Automated refactoring (⚠️ beta)

---

## Technical Documentation

For developers and contributors:

- **[Known Issues](P0_GAP_FIXES_README.md)** - Current bugs and fix status
- **[Functional Equivalence Gaps](FUNCTIONAL_EQUIVALENCE_GAPS.md)** - Detailed gap analysis (1300+ lines)

### Design Documents

Located in `duplication-detector/` subdirectory:

- **[README](duplication-detector/README.md)** - Architecture overview
- **[Design Document](duplication-detector/duplication_detector_design.md)** - Comprehensive technical design
- **[Class Design](duplication-detector/class_design.md)** - Package structure and classes
- **[Sequence Diagrams](duplication-detector/sequence_diagrams.md)** - Visual flow diagrams
- **[Implementation Status](duplication-detector/implementation_task_list.md)** - Current progress

---

## Documentation Status

**Last Updated**: December 31, 2025

### ✅ Current and Accurate

- Quick Start Guide
- Configuration Reference
- Design documents (duplication-detector/)
- Known Issues (P0_GAP_FIXES_README.md)
- Functional Equivalence Gaps

### ⚠️ Beta Features

- Batch Mode Guide - Feature works but has known bugs

---

## Quick Reference

### Common Tasks

**Analyze duplicates**:
```bash
mvn exec:java -Dexec.args="analyze"
```

**Preview refactorings** (safe):
```bash
mvn exec:java -Dexec.args="refactor --mode dry-run"
```

**Export metrics**:
```bash
mvn exec:java -Dexec.args="analyze --export csv"
```

### Configuration Files

- Main config: `src/main/resources/generator.yml`
- Test config: `src/test/resources/analyzer-tests.yml`

### Key Concepts

- **Similarity Threshold**: 0.0-1.0 (default: 0.75 = 75% similar)
- **Min Lines**: Minimum statements to consider (default: 5)
- **Clusters**: Groups of related duplicates
- **Strategies**: Extract Method, BeforeEach, ParameterizedTest, Utility Class

---

## Support

For issues or questions:
1. Check [Known Issues](P0_GAP_FIXES_README.md)
2. Review [Functional Equivalence Gaps](FUNCTIONAL_EQUIVALENCE_GAPS.md)
3. See main [README](../README.md)
4. Open a GitHub issue

---

## Contributing to Documentation

Documentation contributions are welcome! Please:
- Keep docs up-to-date with code changes
- Add examples where helpful
- Use clear, concise language
- Test all code examples

See [Contributing](../README.md#contributing) in main README.

