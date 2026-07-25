# AGENTS.md

이 파일은 에이전트가 이 저장소에서 작업할 때 참고하는 프로젝트 컨텍스트입니다.
사용자 문서는 [README.md](README.md)에 더 자세히 있습니다. 여기엔 **빌드/실행/규칙/주의점**만 압축합니다.

## 프로젝트 한 줄 요약
가로 스크롤 또는 화면 전환 방식의 악보(TAB) 유튜브 영상을 받아 프레임을 이어 붙이고,
완성된 악보 줄을 스트리밍 저장해 **PDF**로 출력하는 Python/Electron 데스크톱 도구.

## 기술 스택
- **데스크톱 UI**: Electron, React, TypeScript, Electron Forge
- **처리 엔진/작업 API**: Python 3.12+, FastAPI, OpenCV, NumPy, ReportLab
- **다운로드**: `yt-dlp` (PATH 또는 `backend/yt-dlp.exe`)
- **패키징**: PyInstaller onedir sidecar + Electron Forge
- **레거시 비교 엔진**: Java 21, Gradle, JavaCV, PDFBox (`backend/`, Electron에 미포함)

## 빌드 / 실행 / 테스트 명령
```bash
# FastAPI 정적 검사 + 테스트
cd python-backend
uv sync --extra dev
uv run ruff check .
uv run mypy
uv run pytest

# FastAPI 단독 개발 실행
YTPDF_API_TOKEN=development-token uv run ytpdf-api

# Python CLI
uv run ytpdf convert --start 00:15 --end 04:45 "<URL>"

# Electron 개발 실행
cd electron
npm install
npm start

# Electron 검증 / 패키징
npm test
npm run package

# 레거시 Java 비교 엔진(앱 패키징에는 불필요)
cd backend
./gradlew test shadowJar

# 개발용 단발 테스트 클래스(JUnit 아님, main 메서드 직접 실행)
#   StitchTest      : 로컬 영상에 스티칭(불투명+화면전환) 적용 → stitchout.pdf
#   OpaqueFrameTest : 불투명 배경 프레임 처리(이진화/특징) 점검
```
> Java JUnit 테스트는 레거시 비교가 필요할 때만 `cd backend && ./gradlew test`로 실행한다.

## 코드 구조

최상위는 Python 처리 엔진·FastAPI인 `python-backend/`, 데스크톱 앱인 `electron/`, 레거시
Java 비교 엔진인 `backend/`로 나뉜다.

### 레거시 Java 비교 엔진 (`backend/src/main/java/com/sheetmusic/`)
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
| `main/` | 창·파일 선택·FastAPI sidecar 수명주기와 IPC 관리 |
| `preload/` | Renderer에 허용된 API만 `contextBridge`로 노출 |
| `renderer/` | React UI |
| `shared/` | Main/Preload/Renderer가 공유하는 요청·이벤트 타입 |

### FastAPI (`python-backend/src/ytpdf_api/`)

| 파일 | 역할 |
|---|---|
| `app.py` | 프리뷰·작업·상태·취소·SSE·결과 API |
| `schemas.py` | Pydantic 요청·응답·이벤트 계약 |
| `jobs.py` | 장시간 작업 상태와 자식 프로세스 수명주기 |
| `engine.py` | 격리된 Python Worker 프로세스 어댑터 |
| `previews.py` | OpenCV를 지연 로드하는 프리뷰 서비스 어댑터 |
| `settings.py` | 환경 변수 기반 로컬 sidecar 설정 |

### Python 처리 코어 (`python-backend/src/ytpdf_core/`)

| 파일 | 역할 |
|---|---|
| `extractor.py` | Java FrameExtractor를 포팅한 스캔·스티칭 상태 머신 |
| `image_ops.py` | 배경별 특징 추출·출력 정리 |
| `downloader.py` | yt-dlp 자식 프로세스 래퍼 |
| `pdf_builder.py` | 스티칭 행을 A4 PDF로 배치 |
| `pipeline.py` | 다운로드→추출→PDF 오케스트레이션 |
| `preview.py` | ROI 설정용 대표 프레임 추출·JPEG 인코딩 |

## 코드 컨벤션
- 패키지: `com.sheetmusic` 아래 역할별 서브패키지
- 주석/로그/사용자 문자열: **한국어**. 로그는 `[태그]` 접두(예: `[조각]`, `[오류]`, `[경고]`)
- 클래스명 PascalCase, 메서드/필드 camelCase. 튜닝용 상수는 `UPPER_SNAKE`(예: `MARGIN`, `TPL_RATIO`)
- Python은 `ruff`·`mypy --strict`를 통과해야 하며 API route에 영상 처리 로직을 넣지 않는다.
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
- 플랫폼 의존 OpenCV wheel을 사용하므로 의존성 변경 시 각 OS 패키징을 확인한다.
- Renderer에서 Node.js API나 전체 `ipcRenderer`를 직접 노출하지 않는다. 파일·프로세스 작업은
  Main에서 수행하고 Preload에는 용도별 메서드만 공개한다.
- FastAPI route 안에서 영상 변환을 직접 실행하지 않는다. `JobManager`가 별도 Worker
  프로세스로 실행하며, 영상 처리 코어는 FastAPI에 의존하지 않게 유지한다.
- ROI 프리뷰의 OpenCV·yt-dlp 처리는 이벤트 루프 밖 작업 스레드에서 실행하고 변환 작업과
  동시에 실행하지 않는다. 이미지 바이트와 API 토큰은 Renderer가 아니라 Main이 처리한다.
- 앱과 FastAPI는 Python 엔진만 사용한다. `backend/` Java 구현은 회귀 비교용이며 JAR나 Java
  런타임을 Electron 리소스에 다시 포함하지 않는다.
- 로컬 API는 `127.0.0.1`의 임의 포트와 프로세스별 토큰을 사용한다. 토큰을 Renderer에
  노출하거나 `0.0.0.0`에 바인딩하지 않는다.
