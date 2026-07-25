# AGENTS.md

이 파일은 에이전트가 이 저장소에서 작업할 때 참고하는 프로젝트 컨텍스트입니다.
사용자 문서는 [README.md](README.md)에 더 자세히 있습니다. 여기엔 **빌드/실행/규칙/주의점**만 압축합니다.

## 프로젝트 한 줄 요약
가로 스크롤 또는 화면 전환 방식의 악보(TAB) 유튜브 영상을 받아 프레임을 이어 붙이고,
완성된 악보 줄을 스트리밍 저장해 **PDF**로 출력하는 Java 데스크톱 도구.

## 기술 스택
- **데스크톱 UI**: Electron, React, TypeScript, Electron Forge
- **백엔드 언어/빌드**: Java 21, Gradle (Shadow 플러그인으로 fat jar). Gradle 실행 JDK도 21 사용.
- **다운로드**: `yt-dlp` (PATH 또는 `backend/yt-dlp.exe`)
- **디코딩/영상처리**: JavaCV(bytedeco) + OpenCV 4.7, FFmpeg는 JavaCV에 번들
- **PDF**: Apache PDFBox 3.0.2
- **플랫폼**: `build.gradle`이 빌드 OS/아키텍처를 감지해 JavaCPP 네이티브 classifier를 선택

## 빌드 / 실행 / 테스트 명령
```bash
# 백엔드 테스트 + fat jar 생성
cd backend
./gradlew test shadowJar
# Windows
gradlew.bat test shadowJar

# 결과물
backend/build/libs/youtube-to-pdf-1.0.0-shaded.jar

# Electron 개발 실행
cd electron
npm install
npm start

# Electron 검증 / 패키징
npm test
npm run package

# 백엔드 CLI
java -jar backend/build/libs/youtube-to-pdf-1.0.0-shaded.jar "<URL>" ["<URL2>" ...]
java -jar backend/build/libs/youtube-to-pdf-1.0.0-shaded.jar --file urls.txt
java -jar backend/build/libs/youtube-to-pdf-1.0.0-shaded.jar --start 00:15 "<URL>"
java -jar backend/build/libs/youtube-to-pdf-1.0.0-shaded.jar --start 00:15 --end 04:45 "<URL>"

# 개발용 단발 테스트 클래스(JUnit 아님, main 메서드 직접 실행)
#   StitchTest      : 로컬 영상에 스티칭(불투명+화면전환) 적용 → stitchout.pdf
#   OpaqueFrameTest : 불투명 배경 프레임 처리(이진화/특징) 점검
```
> **주의**: Windows에서는 앱(jar)이 실행 중이면 파일 잠금으로 빌드가 실패할 수 있다.
> JUnit 테스트는 `backend/src/test/java`에 있으며 `cd backend && ./gradlew test`로 실행한다.

## 코드 구조

최상위는 Java 처리 엔진인 `backend/`와 Electron 데스크톱 앱인 `electron/`으로 나뉜다.
### 백엔드 (`backend/src/main/java/com/sheetmusic/`)
| 파일 | 역할 |
|---|---|
| `Main.java` | CLI 진입점과 인자 파싱 |
| `VideoProcessor.java` | 파이프라인 오케스트레이션 (다운로드→추출→PDF) |
| `YtDlpDownloader.java` | yt-dlp 호출 래퍼 + 설치 확인 |
| `FrameExtractor.java` | ★ 핵심. 프레임 샘플링·blackhat 특징추출·스티칭·배경제거 |
| `Background.java` | enum 축1: `TRANSLUCENT`(반투명) / `OPAQUE`(불투명) — 특징추출·출력 결정 |
| `Motion.java` | enum 축2: `SCROLL`(스크롤) / `CUT`(화면 전환) — 스티칭 전략 결정 |
| `PdfBuilder.java` | 이미지 조각 → PDF |
| `ProgressLogger.java` | 진행 로그 추상화 |
| `CancellationToken.java` | UI 프레임워크 독립 취소 인터페이스 |
| `Config.java` | 공용 설정 |
| `StitchTest.java`, `OpaqueFrameTest.java` | 개발용 수동 테스트 (main 메서드) |

핵심 알고리즘 상세는 README의 "동작 원리" 절 참고. 튜닝 상수는 `ScanParams`에 모여 있다.

### Electron (`electron/src/`)

| 경로 | 역할 |
|---|---|
| `main/` | 창·파일 선택·Java 백엔드 프로세스 수명주기와 IPC 관리 |
| `preload/` | Renderer에 허용된 API만 `contextBridge`로 노출 |
| `renderer/` | React UI |
| `shared/` | Main/Preload/Renderer가 공유하는 요청·이벤트 타입 |

## 코드 컨벤션
- 패키지: `com.sheetmusic` 아래 역할별 서브패키지
- 주석/로그/사용자 문자열: **한국어**. 로그는 `[태그]` 접두(예: `[조각]`, `[오류]`, `[경고]`)
- 클래스명 PascalCase, 메서드/필드 camelCase. 튜닝용 상수는 `UPPER_SNAKE`(예: `MARGIN`, `TPL_RATIO`)
- Java 21 문법 적극 사용: switch 패턴(`case "--file", "-f" -> ...`), 텍스트 블록(`"""`)
- 인코딩 UTF-8. 실행 시 `-Dfile.encoding=UTF-8`, `jna.nosys/jna.protected` 시스템 프로퍼티 설정

## 작업 시 주의점
- **모드는 2축**: `Background`(반투명/불투명)이 특징추출·출력을, `Motion`(스크롤/화면전환)이
  스티칭 전략을 결정. 결과가 이상하면 코드보다 이 조합부터 점검(예: 불투명인데 가로 스크롤이면
  `불투명+스크롤`. `화면전환`으로 두면 '멈춤'이 안 잡혀 누락). 배경=전처리, 진행=스티칭으로 직교.
- 영상마다 결과가 다르면 코드를 고치기 전에 `FrameExtractor` 상단 튜닝 상수부터 조정
  (`MARGIN`↓=누락 방지, `MARGIN`↑=중복 방지). 실행 로그의 `dx`/`score`, 카운트(0이 아닌 것만
  표시: `스크롤/페이지/정지/합의거부/트림`), `[2밴드 거부]` 줄이 튜닝 단서.
- 도돌이(반복) 구간은 픽셀 스티칭으로 중복이 쌓이는 **알려진 한계**. 새 버그로 오해하지 말 것.
- 플랫폼 의존 네이티브(OpenCV/FFmpeg)라 의존성 버전 변경 시 `javacpp.platform`과의 호환을 확인.
- Renderer에서 Node.js API나 전체 `ipcRenderer`를 직접 노출하지 않는다. 파일·프로세스 작업은
  Main에서 수행하고 Preload에는 용도별 메서드만 공개한다.
