# YouTube 악보 영상 → PDF 변환기

가로로 **스크롤되는 TAB/악보 유튜브 영상**을 받아서, 화면을 한 장의 긴 악보로 이어 붙인 뒤
적당한 길이로 잘라 **PDF**로 만들어 주는 도구입니다.

대상 영상 형태:
- 악보(주로 베이스/기타 TAB)가 화면 **하단 띠**에 깔려 있고,
- 재생바(커서)만 움직이다가 줄 끝에서 **가로로 스크롤**되는 형식.
- 배경이 **반투명/투명/뮤비/연주자 영상**이라 대비가 제각각이어도 동작하도록 설계.

---

## 주요 기능

- 유튜브 URL만 넣으면 다운로드 → 프레임 분석 → 스티칭 → PDF까지 자동
- **GUI**(미리보기로 악보 영역 지정 + 진행 로그 + 취소) / **CLI**(여러 URL·배치) 모두 지원
- 반투명·저대비 배경에서도 악보 표기만 추출(배경 제거 → 흰 종이 + 검은 표기)
- 스크롤 중복(겹침) 자동 제거 + 점프 스크롤 누락 방지

---

## 동작 원리

`FrameExtractor`가 핵심입니다. 프레임을 일정 간격으로 샘플링하며 다음을 수행합니다.

1. **특징 추출 (blackhat)** — ROI(하단 악보 띠)에서 모폴로지 *블랙햇*으로 "패널 위 어두운 표기
   (마디선·숫자·기둥)"만 뽑습니다. 국소 대비 기반이라 전역 밝기/투명도와 무관합니다.
2. **확정화면 전폭 매칭** — 직전까지 확정된 화면에 새 프레임을 정렬해 우측 이동량 `dx`를 구하고,
   새로 드러난 픽셀만 이어 붙여 **겹침을 제거**합니다.
3. **마진 게이트 (`peak − zero`)** — "스크롤 위치 상관"이 "제자리(dx=0) 상관"보다 뚜렷이 높을 때만
   스크롤로 인정합니다. 덕분에 **빈/희박한 마디의 미세 스크롤은 잡고**(누락 방지),
   정지·주기적 오매칭은 거릅니다(중복 방지).
4. **시드 갱신** — 첫 스크롤 전(인트로) 구간에서는 더 또렷한 프레임으로 시드를 교체해,
   fade-in 때문에 초반이 하얗게 나오는 문제를 막습니다.
5. **배경 제거 출력** — 완성된 파노라마에 blackhat + CLAHE를 적용해 **흰 종이 + 검은 표기**로 정리한 뒤,
   화면 폭 단위로 잘라 `PdfBuilder`가 PDF로 묶습니다.

> 다운로드는 `yt-dlp`, 디코딩은 JavaCV(번들 FFmpeg), 영상처리는 OpenCV, PDF는 Apache PDFBox를 사용합니다.

---

## 요구 사항

| 항목 | 내용 |
|---|---|
| JDK | **21 이상** (`pom.xml`의 compiler target=21) |
| Maven | 3.8+ |
| yt-dlp | **PATH에 설치 필요** (Windows에선 프로젝트 루트의 `yt-dlp.exe`도 사용 가능) |
| FFmpeg | **불필요** — JavaCV(bytedeco)에 번들됨 |
| OS | 현재 `pom.xml`이 `windows-x86_64`로 고정 (아래 참고) |

설치 예: `pip install yt-dlp` / `winget install yt-dlp` (Windows) / `brew install yt-dlp` (macOS)

### Windows 외 플랫폼에서 빌드하려면
`pom.xml`의 네이티브 플랫폼 고정을 환경에 맞게 바꾸세요.
```xml
<javacpp.platform>windows-x86_64</javacpp.platform>  <!-- 예: macosx-arm64, linux-x86_64 -->
```

---

## 빌드

```bash
mvn -o clean package -DskipTests
```
결과: `target/youtube-to-pdf-1.0.0-shaded.jar` (의존성 포함 실행 가능 fat jar)

> 앱이 실행 중이면 jar이 잠겨 빌드가 실패합니다. 먼저 앱을 종료하세요.

---

## 실행

### GUI (기본)
```bash
java -jar target/youtube-to-pdf-1.0.0-shaded.jar
# 또는
java -jar target/youtube-to-pdf-1.0.0-shaded.jar --gui
```
URL 입력 → 미리보기로 악보 영역(ROI) 확인 → 저장 폴더 지정 → 변환.

### CLI
```bash
# 단일/다중 URL
java -jar target/youtube-to-pdf-1.0.0-shaded.jar "<URL>" ["<URL2>" ...]

# URL 목록 파일(한 줄에 하나, # 주석 가능)
java -jar target/youtube-to-pdf-1.0.0-shaded.jar --file urls.txt

# ROI 지정
java -jar target/youtube-to-pdf-1.0.0-shaded.jar --roi 0.72,1.00,0.00,1.00 "<URL>"
```

| 옵션 | 설명 |
|---|---|
| `--file`, `-f <파일>` | URL 목록 텍스트 파일 |
| `--roi`, `-r <top,bot,left,right>` | 악보 영역 비율 (기본 `0.70,1.00,0.00,1.00`) |
| `--help`, `-h` | 도움말 |

---

## ROI(악보 영역) 설정

화면에서 악보 띠가 차지하는 영역을 `top,bottom,left,right` 비율(0~1)로 지정합니다.
**마디 번호 줄이 잘리지 않게** 띠보다 살짝 위에서 시작하세요.

- 권장 기본값: `0.72,1.00,0.00,1.00` (하단 약 28%)
- 너무 좁게 잡으면(예: `0.81,0.98,...`) 마디 번호가 잘려 정확도가 떨어집니다.

---

## 튜닝 (`FrameExtractor` 상단 상수)

영상마다 안 맞으면 아래 값을 조정하세요.

| 증상 | 조정 |
|---|---|
| 누락이 남음 | `MARGIN` ↓ (예: 0.08), `TPL_RATIO` ↓ |
| 중복이 생김 | `MARGIN` ↑ (예: 0.18) |
| 초반이 하얗게 | `SEED_REFRESH_SCORE` ↓, 출력 CLAHE/게인 조정 |
| 출력 표기가 옅음/진함 | `cleanForOutput`의 대비 게인(`Scalar(3.0)`) |

실행 로그의 `스크롤/페이지/정지` 카운트와 `dx`, `score` 값이 튜닝 단서입니다.

---

## 알려진 한계

- **곡의 도돌이(반복) 구간**은 영상이 같은 마디를 다시 보여주므로 결과에 **중복**으로 쌓입니다.
  픽셀 기반 스티칭으로는 근본 해결이 어렵고, 마디 번호 인식(OCR)이 있어야 깔끔히 정리됩니다.
- 마디 번호 OCR(`MeasureOcr`, Tess4J 기반)은 코드에 포함돼 있으나 **실험적/선택**이며 메인
  파이프라인에 연결돼 있지 않습니다. 사용하려면 시스템에 **Tesseract + tessdata** 설치가 필요합니다.

---

## 프로젝트 구조

```
src/main/java/com/sheetmusic/
├─ Main.java            # 진입점(CLI 파싱 / 인자 없으면 GUI)
├─ GuiApp.java          # Swing GUI (미리보기·ROI·진행·취소)
├─ VideoProcessor.java  # 파이프라인 오케스트레이션
├─ YtDlpDownloader.java # yt-dlp 다운로드 래퍼
├─ FrameExtractor.java  # ★ 프레임 분석·스티칭·배경제거 (핵심)
├─ PdfBuilder.java      # 이미지 → PDF
├─ MeasureDetector.java # (실험적) 마디 단위 검출
├─ MeasureOcr.java      # (실험적) 마디 번호 OCR
└─ Config.java          # 공용 설정
```

> 참고: 저장소 안의 `youtube-to-pdf/` 폴더는 과거의 중복 복사본이며 빌드와 무관합니다(루트 `src/` 사용).

