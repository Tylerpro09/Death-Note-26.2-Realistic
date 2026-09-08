#!/usr/bin/env bash
set -euo pipefail
command -v java >/dev/null || { echo 'Java 25 is required'; exit 1; }
command -v gradle >/dev/null || { echo 'Gradle 9.x is required'; exit 1; }
gradle build --stacktrace
