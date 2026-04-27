#!/bin/sh
# Minimal gradlew wrapper script (truncated, functional)
set -e
DIRNAME=`dirname "$0"`
APP_HOME=`cd "$DIRNAME" && pwd`
exec "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" >/dev/null 2>&1 || java -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
