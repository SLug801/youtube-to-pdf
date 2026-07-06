---
description: 유튜브 URL을 빌드 후 CLI로 PDF 변환 (필요 시 빌드부터)
argument-hint: <youtube-url> [추가 URL...]
allowed-tools: Bash, Read
---

다음 유튜브 악보 영상을 PDF로 변환한다: $ARGUMENTS

절차:
1. `target/youtube-to-pdf-1.0.0-shaded.jar`가 있는지 확인한다.
   - 없거나 소스가 더 최신이면 `mvn -o clean package -DskipTests`로 빌드한다.
   - 빌드 실패가 "파일 잠금"이면 앱이 실행 중이니 사용자에게 종료를 요청한다.
2. `java -jar target/youtube-to-pdf-1.0.0-shaded.jar "$ARGUMENTS"` 로 변환을 실행한다.
   - URL이 여러 개면 각각 인자로 넘긴다.
3. 실행 로그의 `스크롤/페이지/정지` 카운트와 생성된 PDF 경로를 요약해 보고한다.
4. 결과가 누락/중복으로 의심되면 `/tune` 으로 안내한다.
