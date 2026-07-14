#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Stop any existing Gradle daemons that may be using the wrong JDK
./gradlew --stop 2>/dev/null || true

echo "Starting Terrain Diffusion Standalone Explorer..."
echo "Models will be loaded from ~/.minecraft/terrain-diffusion-models/"
echo "GPU inference with model offloading (peak VRAM ~1.5GB)"
echo ""

export LD_LIBRARY_PATH=/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}

./gradlew standaloneExplorer -PuseCuda=true --no-daemon
