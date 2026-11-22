#!/usr/bin/env bash
# Compile et package SudimeTools.
# Dépendances : JDK 21 + jars Paper API (paper-api.jar + libs) dans /tmp/apilibs.
set -euo pipefail
cd "$(dirname "$0")"

JAVAC="${JAVAC:-/tmp/jdk/jdk-21.0.13.1+2/bin/javac}"
JAR="${JAR_TOOL:-/tmp/jdk/jdk-21.0.13.1+2/bin/jar}"
CP="$(ls /tmp/apilibs/*.jar | tr '\n' ':')"
for jar in ../survival/plugins/PlaceholderAPI-*.jar ../survival/plugins/CraftEngine-*.jar; do
  if [[ -f "$jar" ]]; then
    CP="${CP}${jar}:"
  fi
done

rm -rf build
mkdir -p build/classes
"$JAVAC" --release 21 -Xlint:-deprecation -cp "$CP" -d build/classes \
  $(find src/main/java -name '*.java')
cp src/main/resources/plugin.yml build/classes/
"$JAR" --create --file build/SudimeTools.jar -C build/classes .
echo "OK : $(pwd)/build/SudimeTools.jar"
