# AGENTS.md

이 파일은 에이전트가 이 저장소에서 작업할 때 참고하는 프로젝트 컨텍스트입니다.
사용자 문서는 [README.md](README.md)에 더 자세히 있습니다. 여기엔 **빌드/실행/규칙/주의점**만 압축합니다.

## 프로젝트 한 줄 요약
가로 스크롤 또는 화면 전환 방식의 악보(TAB) 유튜브 영상을 받아 프레임을 이어 붙이고,
완성된 악보 줄을 스트리밍 저장해 **PDF**로 출력하는 Python/Electron 데스크톱 도구.

## 기술 스택
- **데스크톱 UI**: Electron, React, TypeScript, Electron Forge
- **처리 엔진/작업 API**: Python 3.12+, FastAPI, OpenCV, NumPy, ReportLab
- **다운로드**: `yt-dlp` (PATH 또는 `electron/vendor/`의 플랫폼별 실행 파일)
- **패키징**: PyInstaller onedir sidecar + Electron Forge

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
```

## 코드 구조

최상위는 Python 처리 엔진·FastAPI인 `python-backend/`와 데스크톱 앱인 `electron/`로 나뉜다.
핵심 알고리즘 상세는 README의 "동작 원리" 절 참고. 튜닝 상수는 `core/params.py`에 모여 있다.

### Electron (`electron/src/`)

| 경로 | 역할 |
|---|---|
| `main/` | 창·파일 선택·FastAPI sidecar 수명주기와 IPC 관리 |
| `preload/` | Renderer에 허용된 API만 `contextBridge`로 노출 |
| `renderer/` | React UI |
| `shared/` | Main/Preload/Renderer가 공유하는 요청·이벤트 타입 |

### FastAPI (`python-backend/src/api/`)

| 파일 | 역할 |
|---|---|
| `app.py` | 프리뷰·작업·상태·취소·SSE·결과 API |
| `schemas.py` | Pydantic 요청·응답·이벤트 계약 |
| `jobs.py` | 장시간 작업 상태와 자식 프로세스 수명주기 |
| `engine.py` | 격리된 Python Worker 프로세스 어댑터 |
| `previews.py` | OpenCV를 지연 로드하는 프리뷰 서비스 어댑터 |
| `settings.py` | 환경 변수 기반 로컬 sidecar 설정 |

### Python 처리 코어 (`python-backend/src/core/`)

| 파일 | 역할 |
|---|---|
| `extractor.py` | 프레임 스캔·스티칭 상태 머신 |
| `image_ops.py` | 배경별 특징 추출·출력 정리 |
| `downloader.py` | yt-dlp 자식 프로세스 래퍼 |
| `pdf_builder.py` | 스티칭 행을 A4 PDF로 배치 |
| `pipeline.py` | 다운로드→추출→PDF 오케스트레이션 |
| `preview.py` | ROI 설정용 대표 프레임 추출·JPEG 인코딩 |

## 코드 컨벤션
- 주석/로그/사용자 문자열: **한국어**. 로그는 `[태그]` 접두(예: `[조각]`, `[오류]`, `[경고]`)
- TypeScript 클래스·컴포넌트는 PascalCase, 메서드·필드는 camelCase
- Python 튜닝용 상수는 `UPPER_SNAKE`(예: `MARGIN`, `TPL_RATIO`)
- Python은 `ruff`·`mypy --strict`를 통과해야 하며 API route에 영상 처리 로직을 넣지 않는다.
- 인코딩은 UTF-8을 사용한다.

## 커밋 규칙
- 기존 커밋 기록과 변경 성격을 확인해 `feat`, `fix`, `refactor`, `docs`, `test`, `chore` 등
  적절한 Conventional Commit 타입을 선택한다.
- 제목은 `type: 한국어 요약` 형식으로 간결하게 작성한다.
- 본문은 제목과 빈 줄로 구분하고, 실제 변경 내용을 `- 내용` 형식으로 나열한다.
- 관련된 변경과 검증이 끝난 의미 있는 작업 단위마다 바로 커밋하며, 서로 무관한 변경은 한
  커밋에 섞지 않는다.
- 기본 커밋 메시지 형식:
  ```text
  feat: 기능 요약

  - 변경 내용
  - 변경 내용
  - 검증 또는 영향
  ```

## 작업 시 주의점
- **모드는 2축**: `Background`(반투명/불투명)이 특징추출·출력을, `Motion`(스크롤/화면전환)이
  스티칭 전략을 결정. 결과가 이상하면 코드보다 이 조합부터 점검(예: 불투명인데 가로 스크롤이면
  `불투명+스크롤`. `화면전환`으로 두면 '멈춤'이 안 잡혀 누락). 배경=전처리, 진행=스티칭으로 직교.
- 영상마다 결과가 다르면 코드를 고치기 전에 `core/params.py`의 튜닝 상수부터 조정
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
- 앱과 FastAPI는 Python 엔진만 사용한다. 플랫폼별 `yt-dlp` 실행 파일은 `electron/vendor/`에서
  관리하고 패키징 시 Python sidecar 리소스에 복사한다.
- 로컬 API는 `127.0.0.1`의 임의 포트와 프로세스별 토큰을 사용한다. 토큰을 Renderer에
  노출하거나 `0.0.0.0`에 바인딩하지 않는다.
