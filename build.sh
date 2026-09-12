#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/classes
find build/classes -type f -delete
find src/main/java -name '*.java' > build/sources.txt
javac --release 22 -encoding UTF-8 -d build/classes @build/sources.txt
# Bundled resources (the waifu portraits) ride the classpath beside the classes,
# so they land in the jar the next line packs from build/classes.
if [ -d src/main/resources ]; then cp -R src/main/resources/. build/classes/; fi
# Enable-Native-Access keeps the JDK from warning on every launch now that the
# Game tab binds a libretro core through FFM. Java 24+ honours it; older
# runtimes ignore the attribute harmlessly.
printf 'Enable-Native-Access: ALL-UNNAMED\n' > build/manifest.txt
jar --create --file build/yoru.jar --manifest build/manifest.txt --main-class dev.yoru.ui.YoruApp -C build/classes .
