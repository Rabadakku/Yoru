@echo off
if exist build\classes rmdir /s /q build\classes
mkdir build\classes
dir /s /b src\main\java\*.java > build\sources.txt
javac --release 22 -encoding UTF-8 -Xlint:all,-serial -d build\classes @build\sources.txt
if errorlevel 1 exit /b 1
jar --create --file build\yoru.jar --main-class dev.yoru.ui.YoruApp -C build/classes .
