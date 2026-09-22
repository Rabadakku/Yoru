#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/classes
find build/classes -type f -delete
find src/main/java -name '*.java' > build/sources.txt
javac --release 22 -encoding UTF-8 -d build/classes @build/sources.txt
# Bundled resources ride the classpath beside the classes,
# so they land in the jar the next line packs from build/classes.
if [ -d src/main/resources ]; then cp -R src/main/resources/. build/classes/; fi
jar --create --file build/yoru.jar --main-class dev.yoru.ui.YoruApp -C build/classes .
