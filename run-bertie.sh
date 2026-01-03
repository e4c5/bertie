#!/bin/bash
# Run BertieCLI with proper classpath

cd "$(dirname "$0")"

# Build if needed
if [ ! -d "target/classes" ]; then
    echo "Building project..."
    mvn -q compile
fi

# Get dependencies classpath
CP="target/classes:$(mvn -q dependency:build-classpath -DincludeScope=runtime | tail -1)"

# Run BertieCLI with all arguments passed through
java -cp "$CP" com.raditha.dedup.BertieCLI "$@"






