#!/bin/sh
# Maven Wrapper startup script
# Downloads Maven if not present

MAVEN_VERSION="3.9.6"
MAVEN_HOME="$HOME/.m2/wrapper/dists/apache-maven-$MAVEN_VERSION"
MAVEN_CMD="$MAVEN_HOME/bin/mvn"

if [ ! -f "$MAVEN_CMD" ]; then
    echo "Downloading Maven $MAVEN_VERSION..."
    mkdir -p "$MAVEN_HOME"
    MAVEN_URL="https://dlcdn.apache.org/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.zip"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$MAVEN_URL" -o /tmp/maven.zip
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$MAVEN_URL" -O /tmp/maven.zip
    else
        echo "ERROR: curl or wget required to download Maven."
        echo "Please install Maven manually: https://maven.apache.org/download.cgi"
        exit 1
    fi
    unzip -qo /tmp/maven.zip -d "$HOME/.m2/wrapper/dists/"
    rm /tmp/maven.zip
    echo "Maven $MAVEN_VERSION installed to $MAVEN_HOME"
fi

exec "$MAVEN_CMD" "$@"
