# SonarQube Integration Guide for Bertie

## Overview

This document explains the SonarQube/SonarCloud integration for the Bertie project and provides guidance on addressing common code quality issues.

## Setup Instructions

### 1. SonarCloud Configuration

The project is configured to use SonarCloud for code quality analysis. The integration is set up through:

- **Configuration File**: `sonar-project.properties` at the project root
- **GitHub Actions**: Automated scanning in `.github/workflows/build.yaml`
- **Coverage Reports**: JaCoCo reports are generated and sent to SonarCloud

### 2. Required Secrets

To enable SonarCloud scanning, the following GitHub secret must be configured:

- `SONAR_TOKEN`: Your SonarCloud authentication token
  - Obtain from: https://sonarcloud.io/account/security
  - Add to: Repository Settings → Secrets and variables → Actions

### 3. SonarCloud Project Setup

1. Go to https://sonarcloud.io
2. Sign in with your GitHub account
3. Import the `e4c5/bertie` repository
4. The project key should be: `e4c5_bertie`
5. Organization should be: `e4c5`

## Common SonarQube Issues and Resolutions

### Code Smells

#### Cognitive Complexity
- **Issue**: Methods with high cognitive complexity (nested loops, conditionals)
- **Resolution**: Break down complex methods into smaller, focused methods
- **Target**: Keep complexity below 15

#### Code Duplication
- **Issue**: Duplicated code blocks
- **Resolution**: Extract common logic into reusable methods/classes
- **Note**: Ironic for a duplicate code detector! Use our own tool to find these.

#### Magic Numbers
- **Issue**: Hard-coded numeric values without explanation
- **Resolution**: Replace with named constants
```java
// Bad
if (similarity > 0.75) { ... }

// Good
private static final double SIMILARITY_THRESHOLD = 0.75;
if (similarity > SIMILARITY_THRESHOLD) { ... }
```

### Bugs

#### Null Pointer Dereference
- **Issue**: Potential NullPointerException
- **Resolution**: 
  - Use `Optional<T>` for nullable return types
  - Add null checks before dereferencing
  - Use `Objects.requireNonNull()` for parameters

#### Resource Leaks
- **Issue**: Resources (files, streams) not properly closed
- **Resolution**: Use try-with-resources
```java
// Bad
FileReader reader = new FileReader(file);
// ... use reader

// Good
try (FileReader reader = new FileReader(file)) {
    // ... use reader
}
```

### Security Vulnerabilities

#### Path Traversal
- **Issue**: User-provided paths without validation
- **Resolution**: Validate and sanitize file paths
```java
Path safePath = Paths.get(basePath).normalize();
if (!safePath.startsWith(basePath)) {
    throw new SecurityException("Path traversal attempt");
}
```

#### SQL Injection (if applicable)
- **Issue**: String concatenation in SQL queries
- **Resolution**: Use PreparedStatement or query builders

### Code Coverage

- **Target**: Maintain >80% code coverage
- **Focus**: Cover critical business logic and edge cases
- **Tools**: JaCoCo generates coverage reports

## Running SonarQube Locally

You can run SonarQube analysis locally using Maven:

```bash
# Ensure SONAR_TOKEN environment variable is set
export SONAR_TOKEN=your-token-here

# Run analysis
mvn clean verify sonar:sonar \
  -Dsonar.projectKey=e4c5_bertie \
  -Dsonar.organization=e4c5 \
  -Dsonar.host.url=https://sonarcloud.io
```

## Quality Gates

The project uses SonarCloud's default quality gate with the following criteria:

- **Coverage on New Code**: ≥ 80%
- **Duplicated Lines**: ≤ 3%
- **Maintainability Rating**: A
- **Reliability Rating**: A
- **Security Rating**: A
- **Security Hotspots Reviewed**: 100%

## Monitoring and Reports

- **SonarCloud Dashboard**: https://sonarcloud.io/project/overview?id=e4c5_bertie
- **Pull Request Decoration**: Quality gate status appears on PRs automatically
- **Branch Analysis**: Main branch is analyzed on every push

## Best Practices

1. **Fix Issues Before Merging**: Address all blocker and critical issues
2. **Review Security Hotspots**: Always review security hotspots, even if marked as low risk
3. **Improve Coverage**: Aim for incremental coverage improvements with each PR
4. **Technical Debt**: Track and plan to address technical debt regularly
5. **Code Smells**: Address code smells proactively to prevent accumulation

## Suppressing False Positives

If SonarQube reports false positives:

```java
@SuppressWarnings("java:S1234") // Explain why this is a false positive
public void method() {
    // ...
}
```

**Note**: Always document why you're suppressing a warning.

## Integration with CI/CD

The GitHub Actions workflow automatically:
1. Builds the project
2. Runs tests
3. Generates JaCoCo coverage reports
4. Sends results to SonarCloud
5. Fails the build if quality gate fails (can be configured)

## Troubleshooting

### Analysis Fails
- Check that `SONAR_TOKEN` secret is set correctly
- Verify SonarCloud project exists and is accessible
- Check workflow logs for specific error messages

### Coverage Not Reported
- Ensure tests are running: `mvn test`
- Verify JaCoCo plugin is generating reports: check `target/site/jacoco/jacoco.xml`
- Confirm the file path is correct in workflow

### Quality Gate Fails
- Review specific issues in SonarCloud dashboard
- Address blocker and critical issues first
- Gradually improve other ratings

## References

- [SonarCloud Documentation](https://docs.sonarcloud.io/)
- [SonarQube Java Rules](https://rules.sonarsource.com/java/)
- [JaCoCo Documentation](https://www.jacoco.org/jacoco/)
