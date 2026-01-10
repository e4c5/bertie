#!/bin/bash
# Run BertieCLI with proper classpath

cd "$(dirname "$0")"

# Build if needed
if [ ! -d "target/classes" ]; then
    echo "Building project..."
    mvn -q compile
fi

# Get dependencies classpath
mvn -q dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=cp.txt
CP="target/classes:$(cat cp.txt)"
rm cp.txt

# Run BertieCLI with all arguments passed through
java -cp "$CP" com.raditha.dedup.cli.BertieCLI "$@"






