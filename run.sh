#!/bin/sh
set -eu
cd "$(dirname "$0")"
./build.sh
exec java -jar build/yoru.jar
