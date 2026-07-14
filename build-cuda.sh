#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Stop any existing Gradle daemons that may be using the wrong JDK
./gradlew --stop 2>/dev/null || true

echo "Building Terrain Diffusion MC (CUDA variant)..."
./gradlew build -PuseCuda=true --no-daemon

JAR=$(ls build/libs/*cuda*.jar 2>/dev/null | head -1)
if [ -n "$JAR" ]; then
    echo ""
    echo "Build successful!"
    echo "Output: $JAR"
    echo ""
    echo "To install: copy the jar to your Minecraft mods/ folder"
    echo "Before launching Minecraft, run:"
    echo "  export LD_LIBRARY_PATH=/usr/lib/x86_64-linux-gnu"
else
    echo "ERROR: No output jar found"
    exit 1
fi
