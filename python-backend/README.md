# FastAPI 백엔드

Electron과 향후 AI Agent가 동일한 작업 API를 사용할 수 있도록 만든 로컬 제어 계층입니다.
변환 엔진은 FastAPI와 분리된 Python OpenCV Worker 프로세스로 실행됩니다.

## 코드 구조

```text
src/
├─ api/                  외부 요청과 작업 수명주기
│  ├─ app.py             FastAPI 라우트 구성
│  ├─ schemas.py         요청·응답 데이터 계약
│  ├─ jobs.py            작업 상태·취소·이벤트 관리
│  ├─ engine.py          별도 Worker 프로세스 실행
│  ├─ previews.py        ROI 프리뷰 연결
│  └─ settings.py        환경 변수 설정
└─ core/                 FastAPI와 무관한 변환 기능
   ├─ pipeline.py        다운로드→추출→PDF 흐름
   ├─ extractor.py       프레임 스캔·스티칭
   ├─ image_ops.py       특징 추출·배경 정리
   ├─ downloader.py      yt-dlp 실행
   ├─ preview.py         대표 프레임 생성
   ├─ pdf_builder.py     PDF 출력
   ├─ models.py          변환 모드·ROI 모델
   └─ params.py          영상 분석 튜닝값
```

의존 방향은 `api → core` 한 방향입니다. `core`는 FastAPI나 Electron을 알지 않으므로 CLI,
테스트 또는 다른 인터페이스에서도 그대로 재사용할 수 있습니다.

## 개발 실행

Python 의존성을 설치하고 API를 실행합니다.

```bash
cd python-backend
uv sync --extra dev
YTPDF_API_TOKEN=development-token uv run ytpdf-api
```

기본 주소는 `http://127.0.0.1:8765`입니다. 토큰을 설정했다면 모든 요청에
`X-YTPDF-Token` 헤더를 전달해야 합니다.

## API

- `GET /health`: API와 선택된 변환 엔진 준비 상태
- `POST /api/v1/preview`: ROI 설정용 JPEG 대표 프레임
- `POST /api/v1/jobs`: 변환 작업 생성
- `GET /api/v1/jobs/{job_id}`: 작업 상태
- `POST /api/v1/jobs/{job_id}/cancel`: 작업 취소
- `GET /api/v1/jobs/{job_id}/events`: 로그와 상태를 SSE로 구독
- `GET /api/v1/jobs/{job_id}/result`: 완료된 PDF 다운로드

영상 변환은 API 프로세스가 아니라 별도 Python Worker 프로세스에서 실행됩니다. 현재 데스크톱
동작과 동일하게 한 번에 한 작업만 허용합니다. 프리뷰와 변환도 동시에 실행하지 않으며,
프리뷰가 내려받은 영상은 같은 출력 폴더의 후속 변환에서 재사용합니다.

## CLI

```bash
# 현재 폴더에 결과 저장
uv run ytpdf convert "<URL>"

# 출력 폴더와 변환 옵션 지정
uv run ytpdf convert \
  --output-directory ./output \
  --start 00:15 \
  --end 04:45 \
  --roi 0.70,1.00,0.00,1.00 \
  --background translucent \
  --motion scroll \
  "<URL>"
```

## 검증

```bash
uv run ruff check .
uv run mypy
uv run pytest
```

## 환경 변수

- `YTPDF_API_HOST`: 바인딩 주소, 기본 `127.0.0.1`
- `YTPDF_API_PORT`: 포트, 기본 `8765`
- `YTPDF_API_TOKEN`: 로컬 API 인증 토큰
- `YTPDF_YTDLP_PATH`: 패키지에 포함된 yt-dlp 경로
