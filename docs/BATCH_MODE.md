# Bertie Batch Mode Guide

> ⚠️ **WARNING**: Batch mode is currently not recommended for production use due to known P0 bugs.
> See [P0_GAP_FIXES_README.md](P0_GAP_FIXES_README.md) for details.
> 
> **Recommended**: Use `--mode dry-run` or `--mode interactive` instead.

## Quick Command

Preview refactorings without making changes (safe):

```bash
mvn exec:java -Dexec.mainClass="com.raditha.dedup.cli.BertieCLI" \
  -Dexec.args="refactor --mode dry-run --verify compile"
```

Apply refactorings with manual review (safer):

```bash
mvn exec:java -Dexec.mainClass="com.raditha.dedup.cli.BertieCLI" \
  -Dexec.args="refactor --mode interactive --verify compile"
```

## Batch Mode (Use with Caution)

Automatically applies high-confidence refactorings without manual review:

```bash
mvn exec:java -Dexec.mainClass="com.raditha.dedup.cli.BertieCLI" \
  -Dexec.args="refactor --mode batch --verify compile"
```

**Known Issues**:
- May use wrong argument values in some cases
- Return value detection can select wrong variable
- Type inference incomplete for complex expressions

**Current Status**: 14 test failures (92% pass rate)

## What Happens

1. Analyzes your codebase for duplicates
2. Generates refactoring recommendations  
3. **Auto-applies** high-confidence refactorings (>70%)
4. Verifies compilation after each change
5. Auto-rolls back if verification fails
6. Skips low-confidence changes

## Modes

| Mode | Description | Use When |
|------|-------------|----------|
| `batch` | Auto-apply high-confidence | CI/CD, large codebases |
| `interactive` | Review each change | First time, learning |
| `dry-run` | Preview only | Testing configuration |

## Verification Levels

| Level | Checks | Speed | Safety |
|-------|--------|-------|--------|
| `none` | Nothing | Fast | ⚠️ Risky |
| `compile` | Compilation | Medium | ✅ Recommended |
| `test` | Full test suite | Slow | 🔒 Safest |

## Example Output

```
=== Refactoring Session Started ===
Mode: BATCH
Clusters to process: 15

Processing cluster #1 (Strategy: EXTRACT_HELPER_METHOD, Confidence: 85%)
  ✓ Refactoring applied to 2 file(s)
  ✓ Verification passed

Processing cluster #2 (Confidence: 65%)
  ⏭️ Skipped: Low confidence for batch mode

=== Session Summary ===
Successful: 8
Skipped: 5
Failed: 2
```

## Configuration

Edit `src/main/resources/generator.yml`:

```yaml
variables:
  projects_folder: ${HOME}/path/to/projects

base_path: ${projects_folder}/my-project
source_packages:
  - "com.mycompany.myapp"
test_packages:
  - "com.mycompany.myapp"
```

## Safety Features

- ✅ Creates backups before changes
- ✅ Verifies compilation
- ✅ Auto-rollback on failure
- ✅ Only high-confidence changes
- ✅ Multi-file support

## Refactoring Strategy

**Default**: `EXTRACT_HELPER_METHOD`

Extracts duplicate code into private helper methods. Works for both source and test files.

## Tips

**First run?** Use dry-run:
```bash
--mode dry-run
```

**More aggressive?** Lower threshold in `generator.yml`:
```yaml
similarity_threshold: 0.75  # Default: 0.85
```

**Focus on specific code:**
```yaml
source_packages:
  - "com.mycompany.core"  # Only these packages
```

## Troubleshooting

**No duplicates found:**
- Lower `similarity_threshold` in config
- Check `source_packages` configuration

**Refactorings skipped:**
- Normal in batch mode (confidence < 70%)
- Use `--mode interactive` to review

**Compilation errors:**
- Automatically rolled back
- Check logs for details  
- May need manual review

## See Also

- [QUICK_START.md](QUICK_START.md) - Full guide
- [README.md](../README.md) - Overview
