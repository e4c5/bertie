# Configuration Reference

Complete guide to configuring the Duplication Detector.

---

## Configuration File

Location: `src/main/resources/generator.yml`

### Basic Structure

```yaml
variables:
  projects_folder: ${HOME}/projects

base_path: /absolute/path/to/your/project

duplication_detector:
  target_class: "com.example.YourClass"
  min_lines: 5
  threshold: 0.75
```

---

## Core Settings

### `base_path` (required)
Absolute path to the project root containing `src/main/java`.

```yaml
base_path: /home/user/my-project
```

Or use variables:
```yaml
variables:
  projects_folder: ${HOME}/projects

base_path: ${projects_folder}/my-app
```

### `target_class` (required)
Fully qualified name of the class to analyze.

```yaml
duplication_detector:
  target_class: "com.example.service.UserService"
```

### `min_lines` (optional)
Minimum number of lines to consider as a duplicate.

- **Default**: 5
- **Range**: 3-20
- **Recommendation**: Start with 5, lower to 3 for more duplicates

```yaml
duplication_detector:
  min_lines: 5
```

### `threshold` (optional)
Similarity threshold as a decimal (0.0 to 1.0).

- **Default**: 0.75 (75% similar)
- **Range**: 0.60-0.95
- **Presets**:
  - `0.90`: Strict (only very similar code)
  - `0.75`: Balanced (recommended)
  - `0.60`: Lenient (catches more duplicates)

```yaml
duplication_detector:
  threshold: 0.75
```

---

## Presets

Use predefined configuration presets via CLI:

```bash
# Strict mode (90% threshold, 5 lines)
--strict

# Lenient mode (60% threshold, 3 lines)
--lenient
```

---

## CLI Overrides

Override YAML settings from command line:

```bash
mvn exec:java -Dexec.mainClass="com.raditha.dedup.cli.DuplicationDetectorCLI" \
  -Dexec.args="analyze --threshold 80 --min-lines 3"
```

**Priority**: CLI args > generator.yml > defaults

---

## Advanced Configuration

### AI-Powered Method Naming

Configure the AI service for intelligent method name generation:

```yaml
ai_service:
  provider: "gemini"
  model: "gemini-2.0-flash-exp"
  api_endpoint: "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
  api_key: "${GEMINI_API_KEY}"
  timeout_seconds: 30
  max_retries: 2
```

**Environment Variable**:
```bash
export GEMINI_API_KEY="your-api-key-here"
```

### Similarity Weights (Advanced)

Fine-tune how similarity is calculated:

```yaml
duplication_detector:
  similarity_weights:
    lcs_weight: 0.4
    levenshtein_weight: 0.3
    structural_weight: 0.3
```

- **LCS** (Longest Common Subsequence): Token-level similarity
- **Levenshtein**: Edit distance
- **Structural**: AST structure matching

**Recommendation**: Use defaults unless you have specific needs

---

## Refactoring Modes

### Interactive Mode (Default)
Review each refactoring before applying.

```bash
refactor --mode interactive
```

**Best for**: Manual review, learning the tool

### Dry-Run Mode
Preview changes without modifying files.

```bash
refactor --mode dry-run
```

**Best for**: Testing, generating reports

### Batch Mode
Auto-apply high-confidence refactorings (>= 90% similarity).

```bash
refactor --mode batch
```

**Best for**: Automation, CI/CD pipelines

---

## Verification Levels

Control post-refactoring verification:

### Compile (Default)
Verify code compiles after refactoring.

```bash
refactor --verify compile
```

### Test
Run full test suite after refactoring.

```bash
refactor --verify test
```

**Warning**: Can be slow for large test suites.

### None
Skip verification (not recommended).

```bash
refactor --verify none
```

---

## Output Formats

### Text (Default)
Human-readable console output.

```bash
analyze
```

### JSON
Machine-readable output for integration.

```bash
analyze --json
```

**Output**:
```json
{
  "duplicates": [...],
  "clusters": [...],
  "metrics": {...}
}
```

---

## Examples

### Strict Analysis
Find only high-confidence duplicates:

```yaml
duplication_detector:
  threshold: 0.90
  min_lines: 7
```

### Lenient Analysis
Catch more potential duplicates:

```yaml
duplication_detector:
  threshold: 0.65
  min_lines: 3
```

### Production Pipeline
Safe batch refactoring:

```yaml
duplication_detector:
  threshold: 0.85
  min_lines: 5
```

```bash
refactor --mode batch --verify test
```

---

## Best Practices

1. **Start Conservative**: Use default threshold (0.75) initially
2. **Review First**: Always run `--mode dry-run` before actual refactoring
3. **Version Control**: Commit before refactoring for easy rollback
4. **Incremental**: Refactor one class at a time
5. **Test**: Use `--verify test` for critical code

---

## Troubleshooting

### Configuration not loading
- Check YAML syntax (indentation matters!)
- Ensure file is at `src/main/resources/generator.yml`

### Variables not resolving
- Use `${VAR_NAME}` syntax
- Check environment variables are set

### AI naming not working
- Verify `GEMINI_API_KEY` is set
- Check internet connection
- Falls back to semantic naming if AI fails

---

## See Also

- [QUICK_START.md](QUICK_START.md) - Quick start guide
- [USER_GUIDE.md](USER_GUIDE.md) - Detailed usage guide
