#!/usr/bin/env bash
# Reproducible dev/test environment setup for Bertie, mirroring .github/workflows/build.yaml.
#
# Prerequisites: JDK 21 (Temurin) and Maven on PATH (`java -version` / `mvn -version` report 21).
#
# Steps:
#   1. Rewrite git@github.com: -> https://github.com/ so the public test-bed submodule clones anonymously.
#   2. Initialize the test-bed submodule.
#   3. Export MAVEN_OPTS (java.util.stream --add-opens/--add-exports) and unset GEMINI_API_KEY.
#   4. Point Maven Central at Google's mirror (repo.maven.apache.org rate-limits shared egress IPs with 429).
#   5. Build+install com.github.e4c5:hql-parser:0.0.15 from source (JitPack serves only its POM, no JAR).
#   6. Warm ~/.m2 with `mvn clean install` (also downloads/builds Antikythera from JitPack).
#
# Usage: scripts/setup-dev-env.sh [--skip-build]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKIP_BUILD=0
[[ "${1:-}" == "--skip-build" ]] && SKIP_BUILD=1

export MAVEN_OPTS="--add-opens java.base/java.util.stream=ALL-UNNAMED --add-exports java.base/java.util.stream=ALL-UNNAMED"
unset GEMINI_API_KEY

echo "==> Checking JDK / Maven"
java -version 2>&1 | grep -q '"21' || { echo "JDK 21 required (found: $(java -version 2>&1 | head -1))"; exit 1; }
mvn -version | grep -q "Java version: 21" || { echo "Maven must run on JDK 21"; exit 1; }

echo "==> SSH -> HTTPS rewrite for GitHub"
git config --global url."https://github.com/".insteadOf git@github.com:

echo "==> Initializing test-bed submodule"
(cd "$REPO_ROOT" && git submodule update --init --recursive)
[[ -f "$REPO_ROOT/test-bed/pom.xml" ]] || { echo "test-bed submodule is empty"; exit 1; }

echo "==> Maven settings (Central mirror)"
mkdir -p "$HOME/.m2"
if [[ ! -f "$HOME/.m2/settings.xml" ]]; then
  cat > "$HOME/.m2/settings.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.2.0 https://maven.apache.org/xsd/settings-1.2.0.xsd">
  <mirrors>
    <mirror>
      <id>google-maven-central</id>
      <name>Google Maven Central mirror</name>
      <url>https://maven-central.storage-download.googleapis.com/maven2/</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
</settings>
EOF
else
  echo "    ~/.m2/settings.xml exists, leaving it untouched"
fi

HQL_VERSION="0.0.15"
HQL_DIR="$HOME/.m2/repository/com/github/e4c5/hql-parser/$HQL_VERSION"
if [[ ! -f "$HQL_DIR/hql-parser-$HQL_VERSION.jar" ]]; then
  echo "==> Building hql-parser $HQL_VERSION from source (JitPack has no JAR for it)"
  WORK="$(mktemp -d)"
  git clone -q --depth 1 --branch "$HQL_VERSION" https://github.com/e4c5/hql-parser.git "$WORK/hql-parser"
  (cd "$WORK/hql-parser" && mvn -B -q package -DskipTests)
  curl -fsSL "https://jitpack.io/com/github/e4c5/hql-parser/$HQL_VERSION/hql-parser-$HQL_VERSION.pom" -o "$WORK/hql-parser.pom"
  rm -f "$HQL_DIR"/*.lastUpdated
  mvn -B -q install:install-file \
    -Dfile="$WORK/hql-parser/target/hql-parser-$HQL_VERSION.jar" \
    -DpomFile="$WORK/hql-parser.pom" \
    -DgroupId=com.github.e4c5 -DartifactId=hql-parser -Dversion="$HQL_VERSION" -Dpackaging=jar
  rm -rf "$WORK"
fi

if [[ $SKIP_BUILD -eq 0 ]]; then
  echo "==> Warming Maven cache: mvn clean install"
  (cd "$REPO_ROOT" && mvn -B clean install)
fi

echo "==> Done. Add to your shell profile:"
echo "    export MAVEN_OPTS=\"$MAVEN_OPTS\""
