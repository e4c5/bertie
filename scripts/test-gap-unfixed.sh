#!/bin/bash
# Usage: ./test-gap-unfixed.sh <gap-number> <package-name>
# Example: ./test-gap-unfixed.sh 9 statementremoval

GAP=$1
PACKAGE=$2

if [ -z "$GAP" ] || [ -z "$PACKAGE" ]; then
  echo "Usage: $0 <gap-number> <package-name>"
  echo "Example: $0 9 statementremoval"
  exit 1
fi

echo "=== Testing Gap $GAP (UNFIXED - Expecting Failures) ==="
echo ""

# Note: We intentionally don't run BertieCLI here yet
# This script just verifies the gap exists by checking that refactoring would break tests
# The actual refactoring will be done manually or in the full TDD cycle

echo "Running tests for package: $PACKAGE"
cd /home/raditha/csi/Antikythera/bertie/test-bed

mvn test -Dtest="*${PACKAGE}*" -q > /tmp/gap-${GAP}-unfixed.log 2>&1
TEST_RESULT=$?

if [ $TEST_RESULT -eq 0 ]; then
  TEST_COUNT=$(grep "Tests run:" /tmp/gap-${GAP}-unfixed.log | tail -1 | grep -oP "Tests run: \K\d+")
  echo "✅ ${TEST_COUNT} tests passed (baseline intact)"
  echo ""
  echo "NOTE: These tests should FAIL after refactoring with unfixed gap"
  echo "      We'll verify this in the full TDD cycle"
else
  echo "❌ Tests failed unexpectedly"
  tail -20 /tmp/gap-${GAP}-unfixed.log
  exit 1
fi

cd ..
