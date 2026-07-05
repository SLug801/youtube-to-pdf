#!/usr/bin/env bash
# macOS/Linux 실행 스크립트.  빌드: ./gradlew shadowJar
# (yt-dlp 는 PATH 에 설치돼 있어야 함:  brew install yt-dlp  /  pip install yt-dlp)
set -e
cd "$(dirname "$0")"

JAR="build/libs/youtube-to-pdf-1.0.0-shaded.jar"
if [ ! -f "$JAR" ]; then
  echo "JAR 파일이 없습니다. 먼저 ./gradlew shadowJar 를 실행하세요."
  exit 1
fi

exec java -Djna.nosys=true -Djna.protected=true -Dfile.encoding=UTF-8 -jar "$JAR" "$@"
