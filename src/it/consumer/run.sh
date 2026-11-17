#!/usr/bin/env bash
# JPMS consumer smoke test: compiles a small named module against the built library jar and runs it on the module
# path and on the class path. Run after `./mvnw package`.
set -euo pipefail

root=$(cd "$(dirname "$0")/../../.." && pwd)
it="$root/src/it/consumer"
out="$root/target/it-consumer"
cd "$root"

version=$(sed -n 's/^version=//p' target/maven-archiver/pom.properties)
jar="target/dynamic-configuration-$version.jar"
./mvnw -q -B -ntp dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/it-runtime-path.txt
deps=$(cat target/it-runtime-path.txt)

rm -rf "$out"
mkdir -p "$out/classes"
javac --release 17 -Xlint:all -Werror --module-path "$jar:$deps" -d "$out/classes" $(find "$it/src" -name '*.java')
cp -R "$it/resources/." "$out/classes/"

echo "== library module descriptor"
jar --describe-module --file "$jar"

echo "== jdeps: requires that are missing or unused"
jdeps --multi-release 17 --module-path "$jar:$deps" --check io.descoped.dynamic.config

echo "== module path"
java --module-path "$jar:$deps:$out/classes" --module consumer/consumer.Main module

echo "== class path"
java --class-path "$jar:$deps:$out/classes" consumer.Main classpath
