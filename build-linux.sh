#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/mod"
if ! command -v java >/dev/null 2>&1; then echo 'Java 25 is required.' >&2; exit 1; fi
JAVA_MAJOR="$(java -version 2>&1 | awk -F '[".]' '/version/ {print $2; exit}')"
if [[ "${JAVA_MAJOR}" != "25" ]]; then echo "Java 25 is required; found Java ${JAVA_MAJOR:-unknown}." >&2; exit 1; fi
if ! command -v gradle >/dev/null 2>&1; then echo 'Gradle is required. Import the official NeoForge 26.2 MDK wrapper or install Gradle.' >&2; exit 1; fi
gradle clean build
