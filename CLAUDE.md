# CLAUDE.md

이 파일은 Claude Code(및 에이전트)가 이 저장소에서 작업할 때 참고하는 프로젝트 컨텍스트입니다.
사용자 문서는 [README.md](README.md)에 더 자세히 있습니다. 여기엔 **빌드/실행/규칙/주의점**만 압축합니다.

## 프로젝트 한 줄 요약
가로 스크롤되는 악보(TAB) 유튜브 영상을 받아 프레임을 이어 붙여(stitching) 한 장의 긴 악보로
만들고, 화면 폭 단위로 잘라 **PDF**로 출력하는 Java 데스크톱 도구.

## 기술 스택
- **언어/빌드**: Java 21, Maven (shade 플러그인으로 fat jar)
- **다운로드**: `yt-dlp` (PATH 또는 루트의 `yt-dlp.exe`)
- **디코딩/영상처리**: JavaCV(bytedeco) + OpenCV 4.7, FFmpeg는 JavaCV에 번들
- **PDF**: Apache PDFBox 3.0.2
- **플랫폼 고정**: `pom.xml`의 `javacpp.platform`이 `windows-x86_64`로 하드코딩
  (다른 OS에서 빌드하려면 이 값을 `macosx-arm64` 등으로 바꿔야 함)

## 빌드 / 실행 / 테스트 명령
```bash
# 빌드 (테스트 스킵, fat jar 생성)
mvn -o clean package -DskipTests
# 또는 Windows 래퍼
build.bat            # mvnw.cmd package -DskipTests

# 결과물
target/youtube-to-pdf-1.0.0-shaded.jar

# 실행 (GUI) — 인자 없이 실행하면 GUI
java -jar target/youtube-to-pdf-1.0.0-shaded.jar
run.bat              # JNA/인코딩 옵션 포함 실행 래퍼

# 실행 (CLI)
java -jar target/youtube-to-pdf-1.0.0-shaded.jar "<URL>" ["<URL2>" ...]
java -jar target/youtube-to-pdf-1.0.0-shaded.jar --file urls.txt
java -jar target/youtube-to-pdf-1.0.0-shaded.jar --roi 0.72,1.00,0.00,1.00 "<URL>"

# 개발용 단발 테스트 클래스(JUnit 아님, main 메서드 직접 실행)
#   StitchTest      : 로컬 영상에 스티칭+불투명 모드 적용 → stitchout.pdf
#   OpaqueFrameTest : 불투명(페이지 플립) 프레임 처리 점검
```
> **주의**: 앱(jar)이 실행 중이면 파일 잠금으로 `package`가 실패한다. 빌드 전 앱을 종료할 것.
> 정식 단위테스트는 없다. `-DskipTests`가 기본이고, 검증은 위 `*Test` main 클래스를 직접 돌린다.

## 코드 구조 (`src/main/java/com/sheetmusic/`)
| 파일 | 역할 |
|---|---|
| `Main.java` | 진입점. CLI 인자 파싱, 인자 없으면 GUI 실행 |
| `GuiApp.java` | Swing GUI (미리보기·ROI 지정·진행 로그·취소) |
| `VideoProcessor.java` | 파이프라인 오케스트레이션 (다운로드→추출→PDF) |
| `YtDlpDownloader.java` | yt-dlp 호출 래퍼 + 설치 확인 |
| `FrameExtractor.java` | ★ 핵심. 프레임 샘플링·blackhat 특징추출·스티칭·배경제거 |
| `SheetMode.java` | enum: `TRANSLUCENT`(반투명) / `OPAQUE`(불투명/페이지플립) |
| `PdfBuilder.java` | 이미지 조각 → PDF |
| `ProgressLogger.java` | 진행 로그 추상화 (console / GUI) |
| `Config.java` | 공용 설정 |
| `StitchTest.java`, `OpaqueFrameTest.java` | 개발용 수동 테스트 (main 메서드) |

핵심 알고리즘 상세(blackhat → 전폭 매칭 → 마진 게이트 → 시드 갱신 → 배경 제거)는
README의 "동작 원리" 절 참고. 튜닝 상수는 `FrameExtractor` 상단에 모여 있다.

## 코드 컨벤션
- 패키지: `com.sheetmusic` 단일 패키지 (서브패키지 없음)
- 주석/로그/사용자 문자열: **한국어**. 로그는 `[태그]` 접두(예: `[조각]`, `[오류]`, `[경고]`)
- 클래스명 PascalCase, 메서드/필드 camelCase. 튜닝용 상수는 `UPPER_SNAKE`(예: `MARGIN`, `TPL_RATIO`)
- Java 21 문법 적극 사용: switch 패턴(`case "--file", "-f" -> ...`), 텍스트 블록(`"""`)
- 인코딩 UTF-8. 실행 시 `-Dfile.encoding=UTF-8`, `jna.nosys/jna.protected` 시스템 프로퍼티 설정

## 작업 시 주의점
- 영상마다 결과가 다르면 코드를 고치기 전에 `FrameExtractor` 상단 튜닝 상수부터 조정
  (`MARGIN`↓=누락 방지, `MARGIN`↑=중복 방지). 실행 로그의 `dx`/`score`/`스크롤·페이지·정지`
  카운트가 튜닝 단서.
- 도돌이(반복) 구간은 픽셀 스티칭으로 중복이 쌓이는 **알려진 한계**. 새 버그로 오해하지 말 것.
- 플랫폼 의존 네이티브(OpenCV/FFmpeg)라 의존성 버전 변경 시 `javacpp.platform`과의 호환을 확인.
