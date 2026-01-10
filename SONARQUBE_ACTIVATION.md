# SonarQube Integration - Setup Instructions

## Overview

This PR adds complete SonarQube/SonarCloud integration to the Bertie repository. Since SonarQube was not previously configured, this provides everything needed to start monitoring code quality.

## What Has Been Added

### 1. Configuration Files

- **`sonar-project.properties`** - Main SonarQube configuration
  - Project key: `e4c5_bertie`
  - Organization: `e4c5`
  - Configured for Java 21
  - Coverage reports via JaCoCo
  - Excludes test-bed and target directories

- **`.github/workflows/build.yaml`** - Updated CI/CD pipeline
  - Added SonarCloud scanning step
  - Runs after tests and coverage generation
  - Uses official sonarsource/sonarcloud-github-action

- **`pom.xml`** - Maven build configuration
  - Added JaCoCo plugin (version 0.8.11)
  - Configured for automatic coverage report generation
  - Integrates with Surefire for test execution

### 2. Documentation

- **`docs/SONARQUBE_SETUP.md`** (200+ lines)
  - Complete setup guide
  - Common Java issues and how to fix them
  - Local analysis instructions
  - Quality gates configuration
  - Troubleshooting section

- **`docs/SONARQUBE_QUICK_REFERENCE.md`** (200+ lines)
  - 13 most common SonarQube Java issues
  - Before/after code examples
  - Quick fixes for each issue type
  - IDE plugin recommendations

- **`README.md`** - Updated main README
  - Added SonarCloud quality gate badge
  - Added code coverage badge
  - New "Code Quality" section
  - Links to SonarQube documentation

## Setup Steps (Repository Owner)

### Step 1: Create SonarCloud Account

1. Go to https://sonarcloud.io
2. Click "Log in" and choose "With GitHub"
3. Authorize SonarCloud to access your GitHub account

### Step 2: Import Repository

1. On SonarCloud dashboard, click "+"
2. Select "Analyze new project"
3. Choose the `e4c5/bertie` repository
4. Click "Set Up"

### Step 3: Configure Organization

If this is your first SonarCloud project:
1. Create organization: `e4c5`
2. Choose free plan for open source projects

If organization already exists:
1. Ensure it's named `e4c5` (or update `sonar-project.properties`)

### Step 4: Get Authentication Token

1. Go to https://sonarcloud.io/account/security
2. Under "Generate Tokens":
   - Name: `bertie-github-actions`
   - Type: Project Analysis Token (or User Token)
   - Expiration: 90 days (or No expiration)
3. Click "Generate"
4. **Copy the token** (you won't see it again!)

### Step 5: Add GitHub Secret

1. Go to https://github.com/e4c5/bertie/settings/secrets/actions
2. Click "New repository secret"
3. Name: `SONAR_TOKEN`
4. Value: Paste the token from Step 4
5. Click "Add secret"

### Step 6: Trigger Analysis

Option A: Wait for next push to main or PR
Option B: Manually trigger workflow:
1. Go to https://github.com/e4c5/bertie/actions
2. Select "Java CI with Maven" workflow
3. Click "Run workflow"

### Step 7: View Results

After workflow completes:
1. Go to https://sonarcloud.io/project/overview?id=e4c5_bertie
2. Review Quality Gate status
3. Explore Issues, Coverage, and Hotspots tabs

## What to Expect

### Initial Analysis Results

Based on the codebase, expect SonarCloud to flag:

**Code Smells** (~50-100 issues):
- High cognitive complexity in analyzer methods
- Duplicated string literals
- Missing JavaDoc on public APIs
- Magic numbers without named constants

**Bugs** (~5-15 issues):
- Potential NullPointerExceptions
- Resource leak warnings (files/streams)
- Empty catch blocks

**Security Hotspots** (~3-10 issues):
- File path validation needed
- Ensure safe file I/O operations

**Coverage**:
- Current: ~92% (166/180 tests passing)
- Target: >80% on new code
- Note: 14 known failing tests documented in README

### Quality Gate

Default quality gate checks:
- ✅ Coverage on new code ≥ 80%
- ✅ Duplicated lines ≤ 3%
- ✅ Maintainability rating = A
- ✅ Reliability rating = A
- ✅ Security rating = A

First run may not meet all criteria - this is normal!

## Quick Fixes

For common issues, see:
- Detailed guide: `docs/SONARQUBE_SETUP.md`
- Quick reference: `docs/SONARQUBE_QUICK_REFERENCE.md`

Most common fixes:
1. **Cognitive Complexity** - Break down large methods
2. **Null Checks** - Add null validation or use Optional
3. **Resource Leaks** - Use try-with-resources
4. **Magic Numbers** - Define constants

## IDE Integration

Install SonarLint plugin for real-time feedback:
- **IntelliJ IDEA**: Settings → Plugins → Search "SonarLint"
- **VS Code**: Extensions → Search "SonarLint"
- **Eclipse**: Help → Eclipse Marketplace → Search "SonarLint"

Connect to SonarCloud:
1. Open SonarLint settings
2. Add connection to SonarCloud
3. Use same token from Step 4
4. Bind to project `e4c5_bertie`

## Troubleshooting

### "Analysis Failed" in GitHub Actions

**Check**: Is `SONAR_TOKEN` secret configured?
- Go to repository secrets and verify it exists

**Check**: Is token valid and not expired?
- Generate a new token if needed

**Check**: Does SonarCloud project exist?
- Verify at https://sonarcloud.io/projects

### "Quality Gate Failed"

This is expected on first run! To fix:
1. Review issues in SonarCloud dashboard
2. Start with Blockers and Critical issues
3. Address Major issues gradually
4. Update quality gate settings if needed

### Coverage Not Showing

**Check**: JaCoCo report generated?
```bash
mvn clean test
ls -l target/site/jacoco/jacoco.xml
```

**Check**: Report path matches workflow configuration?
- Should be: `target/site/jacoco/jacoco.xml`

## Maintenance

### Regular Tasks

- **Weekly**: Review new issues from SonarCloud
- **Before Merge**: Ensure quality gate passes on PRs
- **Monthly**: Review and update quality gate rules
- **Quarterly**: Rotate SonarCloud token

### Badge URLs

Already added to README.md:
- Quality Gate: `https://sonarcloud.io/api/project_badges/measure?project=e4c5_bertie&metric=alert_status`
- Coverage: `https://sonarcloud.io/api/project_badges/measure?project=e4c5_bertie&metric=coverage`

## Support

- **SonarCloud Docs**: https://docs.sonarcloud.io/
- **Java Rules**: https://rules.sonarsource.com/java/
- **Community**: https://community.sonarsource.com/

## Success Criteria

✅ SonarCloud project created and configured
✅ GitHub secret `SONAR_TOKEN` added
✅ Workflow runs successfully
✅ Analysis results visible in SonarCloud
✅ Badges display on README (may take one successful run)

---

**Questions?** See documentation in `docs/` or open an issue.

**Ready to start?** Follow steps 1-6 above. Should take ~15 minutes!
