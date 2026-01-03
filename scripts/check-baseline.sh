#!/bin/bash

echo "=== Baseline Check ==="
echo ""

echo "1. Running bertie tests..."
cd /home/raditha/csi/Antikythera/bertie
if mvn test -q; then
  echo "✅ Bertie tests passed (136 tests)"
else
  echo "❌ Bertie tests failed"
  exit 1
fi

echo ""
echo "2. Running test-bed baseline tests..."
cd test-bed
if mvn test -q; then
  echo "✅ Test-bed baseline passed (58 tests)"
else
  echo "❌ Test-bed tests failed"
  exit 1
fi

echo ""
echo "=== ✅ Baseline check complete ==="
cd ../..


