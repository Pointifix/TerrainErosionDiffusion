#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

MODS_DIR="$HOME/.minecraft/mods"
MOD_NAME="terrain-diffusion-mc"

# Build if needed or if jar doesn't exist
JAR=$(ls build/libs/*cuda*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
    echo "No built jar found, building first..."
    ./build-cuda.sh
    JAR=$(ls build/libs/*cuda*.jar 2>/dev/null | head -1)
fi

# Remove old versions of the mod
mkdir -p "$MODS_DIR"
rm -f "$MODS_DIR"/${MOD_NAME}*.jar

# Deploy new jar
cp "$JAR" "$MODS_DIR/"
echo "Deployed: $(basename "$JAR") -> $MODS_DIR/"

# Launch Minecraft with CUDA/cuDNN library path
export LD_LIBRARY_PATH=/usr/lib/x86_64-linux-gnu:${LD_LIBRARY_PATH:-}
echo "Launching Minecraft..."
exec minecraft-launcher
