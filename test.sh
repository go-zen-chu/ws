#!/usr/bin/env bash

set -eux

echo "Test version"
pushd .
cd src/version
./test.sh
popd

echo "All test passed"
exit 0
