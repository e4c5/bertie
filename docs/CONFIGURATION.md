# Configuration Reference

Complete guide to configuring the Duplication Detector (Bertie).

---

## Configuration File

Location: `src/main/resources/generator.yml` (default) or custom file via CLI.

### Basic Structure

```yaml
variables:
  projects_folder: ${HOME}/projects
  m2_folder: ${HOME}/.m2/repository

base_path: /absolute/path/to/your/project

duplication_detector:
  target_class: "com.example.YourClass"
  min_lines: 5
  threshold: 0.75
  enable_lsh: true
  max_window_growth: 5
  maximal_only: true
```

---

## Core Settings

### `base_path` (required)
Absolute path to the project root containing `src/main/java`.

```yaml
base_path: /home/user/my-project
```

### `target_class` (optional)
Fully qualified name of the class to analyze. If omitted, all classes in `base_path` are analyzed.

```yaml
duplication_detector:
  target_class: "com.example.service.UserService"
```

### `min_lines` (optional)
Minimum number of lines to consider as a duplicate.

- **Default**: 5
- **Range**: 3-20
- **CLI**: `--min-lines <n>`

### `threshold` (optional)
Similarity threshold as a decimal (0.0 to 1.0) or percentage (0-100).

- **Default**: 0.75 (75%)
- **CLI**: `--threshold <n>`

---

## Advanced Detection Settings

### `enable_lsh`
**Locality Sensitive Hashing (LSH)** is a performance optimization for candidate generation.

- **Default**: `true`
- **Explanation**: 
  When enabled, Bertie uses LSH to quickly find similar code blocks (candidates) instead of performing a brute-force O(N²) comparison of all possible statement sequences. This is essential for large codebases where the number of possible pairs grows exponentially.
  - **Pros**: Significant speedup for large projects; reduces memory pressure.
  - **Cons**: Small chance of missing some duplicates (false negatives) depending on the number of hash functions and bands.
- **Set to `false`** if you suspect missed duplicates and have a small codebase where performance is less critical.

### `max_window_growth`
Limits how many statements a sliding window can grow during candidate expansion.

- **Default**: `5`
- **Explanation**:
  After finding a seed duplicate (e.g., 5 identical lines), Bertie tries to expand the window forward to include more matching statements. This setting prevents the expander from trying too many combinations when the code is highly repetitive but not identical.
  - **Lower values (2-3)**: Faster analysis, but might miss very long, multi-page duplicates if they contain many small variations.
  - **Higher values (7-10)**: Better at finding long duplicates with intermittent variations (like large boilerplate methods), but can significantly increase processing time for repetitive patterns.

### `maximal_only`
Filters whether to report all overlapping duplicates or only the longest ones.

- **Default**: `true`
- **Explanation**:
  A "maximal" sequence is a duplicate that cannot be extended in either direction without dropping below the similarity threshold.
  - **`true` (Recommended)**: Only reports the longest overlapping sequences. If lines 10-20 match lines 50-60, it won't also report 11-19 matching 51-59 as separate duplicates. This provides a much cleaner report.
  - **`false`**: Reports every sub-sequence that meets the threshold. This can be useful for identifying smaller reusable patterns within larger duplicated blocks, but it usually generates a massive amount of "noise" in the report.

---

## Similarity Weights

Fine-tune how similarity is calculated:

```yaml
duplication_detector:
  similarity_weights:
    lcs: 0.4
    levenshtein: 0.3
    structural: 0.3
```

- **`lcs`** (Longest Common Subsequence): Measures how much of the token stream matches in order.
- **`levenshtein`**: Standard edit distance between token streams.
- **`structural`**: Compares the AST node types (ignores variable names/literals).

---

## Refactoring Settings

### Refactoring Modes (`--mode <mode>`)

| Mode | Description |
| :--- | :--- |
| `interactive` | **(Default)** Asks for confirmation before each refactoring. |
| `batch` | Automatically applies all refactorings (high-confidence only). |
| `dry-run` | Shows what would be changed without modifying any files. |

### Verification Levels (`--verify <level>`)

| Level | Description |
| :--- | :--- |
| `compile` | **(Default)** Runs `mvn compile` after each batch of changes. |
| `fast_compile`| Uses the JDK Compiler API to verify the specific modified files (much faster). |
| `test` | Runs `mvn test` to ensure functional correctness. |
| `none` | Skips all verification (high risk). |

---

## CLI-Specific Options

- `--config-file <path>`: Use a custom YAML configuration.
- `--base-path <path>`: Override the project root.
- `--output <path>`: Directory for reports and exported metrics.
- `--resume`: Resumes a previously interrupted refactoring session using `.bertie/last_session.json`.
- `--json`: Output detection results to stdout as JSON.
- `--export <format>`: Export metrics as `csv`, `json`, or `both`.
- `--java-version <v>`: Target Java version for `fast_compile` (e.g., 17, 21).
- `--java-home <path>`: Path to a specific JDK for compilation.

---

## Presets

- `--strict`: Preset for `threshold: 0.90, min_lines: 5`.
- `--lenient`: Preset for `threshold: 0.60, min_lines: 3`.

---

## Exclude Patterns

Exclude specific files or directories from analysis:

```yaml
duplication_detector:
  exclude_patterns:
    - "**/test/**"
    - "**/*Test.java"
    - "**/target/**"
```

Common defaults: `**/test/**`, `**/*Test.java`, `**/target/**`, `**/build/**`, `**/.git/**`.

---

## AI Service (Optional)

Used for intelligent method name generation.

```yaml
ai_service:
  provider: "gemini"
  model: "gemini-2.0-flash-exp"
  api_key: "${GEMINI_API_KEY}"
```

---

## Troubleshooting

1. **Memory Issues**: Enable `enable_lsh` and set `maximal_only: true`.
2. **Slow Analysis**: Increase `min_lines` to reduce the number of initial candidates.
3. **No Duplicates Found**: Try the `--lenient` preset or lower the `threshold` to `0.65`.
4. **Compilation Errors**: Use `--verify fast_compile` for safe automated refactoring.
