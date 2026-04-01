#!/bin/bash
#
# JVMMonitor - Build script (Linux)
# Produces: jvmmonitor.so + jvmmonitor.jar
#
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/build"
DIST_DIR="$SCRIPT_DIR/dist/linux"

echo "=== JVMMonitor Build (Linux) ==="

# 1. Build native agent
echo "Building native agent..."
mkdir -p "$BUILD_DIR"
cmake -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=Release "$SCRIPT_DIR"
cmake --build "$BUILD_DIR" --config Release

# 2. Build Java collector
echo "Building Java collector..."
cd "$SCRIPT_DIR/collector"
mvn clean package -q

# 3. Assemble dist
echo "Assembling distribution..."
mkdir -p "$DIST_DIR"
cp "$BUILD_DIR/agent/jvmmonitor.so" "$DIST_DIR/"
cp "$SCRIPT_DIR/collector/target/jvmmonitor-collector-"*-SNAPSHOT.jar "$DIST_DIR/jvmmonitor.jar" 2>/dev/null || \
cp "$SCRIPT_DIR/collector/target/jvmmonitor-collector-"*.jar "$DIST_DIR/jvmmonitor.jar"

echo ""
echo "=== Build complete ==="
echo "  Agent:     $DIST_DIR/jvmmonitor.so"
echo "  Collector: $DIST_DIR/jvmmonitor.jar"
echo ""
echo "Usage:"
echo "  java -jar $DIST_DIR/jvmmonitor.jar --attach <PID>"
echo "  # or"
echo "  java -agentpath:$DIST_DIR/jvmmonitor.so=host=127.0.0.1,port=9090 -jar your-app.jar"
