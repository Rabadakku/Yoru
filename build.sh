#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/classes
find build/classes -type f -delete
find src/main/java -name '*.java' > build/sources.txt
# Every warning is on except serial: Swing components are Serializable, and
# Yoru never serializes one, so a serialVersionUID on each would be noise.
# CI sets YORU_WERROR=1 so a new warning fails the build there; a newer JDK
# with new warnings still builds from source everywhere else.
javac --release 22 -encoding UTF-8 -Xlint:all,-serial ${YORU_WERROR:+-Werror} -d build/classes @build/sources.txt
# Bundled resources ride the classpath beside the classes,
# so they land in the jar the next line packs from build/classes.
if [ -d src/main/resources ]; then cp -R src/main/resources/. build/classes/; fi
jar --create --file build/yoru.jar --main-class dev.yoru.ui.YoruApp -C build/classes .
