#!/bin/sh
# snapvm smoke-test suite — every PR must run this and keep it green.
cd "$(dirname "$0")"
if [ -x ./gradlew ]; then
	exec ./gradlew test --console=plain
elif command -v gradle >/dev/null 2>&1; then
	exec gradle test --console=plain
else
	echo "no gradlew or gradle found" >&2
	exit 1
fi
