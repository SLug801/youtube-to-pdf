[README.md](https://github.com/user-attachments/files/28663167/README.md)
# 🎸 YoruTab — YouTube TAB 악보 → PDF 변환기

> zzero gu 님 같은 TAB 커버 영상에서 악보만 쏙 뽑아 PDF로 저장해주는 도구

<br>

## ✨ 주요 기능

- 🔗 YouTube URL만 넣으면 자동으로 영상 다운로드
- 🖱️ 영상 미리보기에서 악보 영역을 **드래그로 직접 지정**
- 🔍 악보가 넘어가는 순간만 감지해서 캡처 (중복 없음)
- 📄 캡처된 프레임을 **PDF 한 파일로 자동 합치기**
- 👁️ 변환 과정을 실시간 미리보기로 확인

<br>

## 🖥️ UI 미리보기

```
┌─────────────────────────────────────────────────────────┐
│  🎸 YoruTab                                             │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  YouTube URL  [____________________________] [불러오기] │
│                                                         │
│  ┌──────────────────────┐  ┌──────────────────────┐    │
│  │                      │  │  변환 미리보기        │    │
│  │   영상 미리보기       │  │  ┌────┐┌────┐┌────┐ │    │
│  │   (드래그로 영역 선택)│  │  │ 1  ││ 2  ││ 3  │ │    │
│  │   ╔══════════╗       │  │  └────┘└────┘└────┘ │    │
│  │   ║  TAB 영역║       │  │  ┌────┐┌────┐       │    │
│  │   ╚══════════╝       │  │  │ 4  ││ 5  │  ...  │    │
│  └──────────────────────┘  └──────────────────────┘    │
│                                                         │
│  저장 파일명  [yorushika_01________________]            │
│  저장 위치    [C:\Users\...\output_________] [찾아보기] │
│  유사도 임계값 ───●────────────────────── 0.97         │
│                                                         │
│              [ 🚀 PDF 변환 시작 ]                       │
│                                                         │
│  ████████████████░░░░░░░░  64%  (32 / 50 프레임)       │
└─────────────────────────────────────────────────────────┘
```

<br>

## 🚀 시작하기

### 필수 요구사항

| 도구 | 버전 | 다운로드 |
|------|------|----------|
| Java | 17+ | [Adoptium](https://adoptium.net) |
| Maven | 3.6+ | [maven.apache.org](https://maven.apache.org) |
| yt-dlp | 최신 | `pip install yt-dlp` |

### 설치 및 빌드

```bash
# 저장소 클론
git clone https://github.com/yourname/yorutab.git
cd yorutab

# 빌드
mvn package -q

# 실행 (CLI)
java -jar target/youtube-to-pdf-1.0.0.jar "https://youtu.be/XXXXX"
```

<br>

## 📖 사용법

### GUI 모드 (권장)
```bash
java -jar target/youtube-to-pdf-1.0.0.jar
```
1. YouTube URL 입력 후 **불러오기**
2. 미리보기에서 TAB 악보 영역을 드래그로 선택
3. 저장 파일명 / 위치 지정
4. **PDF 변환 시작** 클릭

### CLI 모드

```bash
# 영상 1개
java -jar target/youtube-to-pdf-1.0.0.jar "https://youtu.be/XXXXX"

# 여러 개 한번에
java -jar target/youtube-to-pdf-1.0.0.jar "https://youtu.be/AAA" "https://youtu.be/BBB"

# URL 목록 파일로 일괄 처리
java -jar target/youtube-to-pdf-1.0.0.jar --file urls.txt

# ROI 영역 직접 지정 (top,bottom,left,right 비율)
java -jar target/youtube-to-pdf-1.0.0.jar --roi 0.65,1.00,0.00,1.00 "https://youtu.be/XXXXX"
```

### CLI 옵션

| 옵션 | 설명 | 기본값 |
|------|------|--------|
| `--threshold`, `-t` | 프레임 유사도 임계값 (낮을수록 더 많이 캡처) | `0.97` |
| `--roi`, `-r` | 악보 영역 비율 `top,bottom,left,right` | `0.70,1.00,0.00,1.00` |
| `--file`, `-f` | URL 목록 텍스트 파일 | - |

<br>

## ⚙️ ROI 설정 가이드

zzero gu 님 영상처럼 **연주 영상 하단에 TAB이 나오는 구조**라면 악보 영역만 비교해야 정확해요.

```
┌─────────────────────────┐  ← 영상 전체
│                         │
│    연주자 영상           │  ← 비교 제외 영역
│                         │
├─────────────────────────┤  ← top 비율 (예: 0.70)
│  ♩ TAB 악보 영역 ♩      │  ← 이 부분만 비교
└─────────────────────────┘  ← bottom 비율 (1.00)
```

| 영상 유형 | 권장 ROI |
|-----------|----------|
| 하단 30% TAB | `0.70,1.00,0.00,1.00` |
| 하단 35% TAB | `0.65,1.00,0.00,1.00` |
| 전체화면 악보 | `0.00,1.00,0.00,1.00` |

<br>

## 🛠️ 기술 스택

- **Java 17**
- **OpenCV** — 프레임 추출 및 유사도 비교
- **Apache PDFBox** — PDF 생성
- **yt-dlp** — YouTube 영상 다운로드
- **Maven** — 빌드 및 의존성 관리

<br>

## 📁 프로젝트 구조

```
yorutab/
├── src/main/java/com/sheetmusic/
│   ├── Main.java              # 진입점, CLI 파싱
│   ├── Config.java            # 설정 상수
│   ├── VideoProcessor.java    # 전체 프로세스 조율
│   ├── YtDlpDownloader.java   # 영상 다운로드
│   ├── FrameExtractor.java    # ROI 기반 프레임 추출
│   └── PdfBuilder.java        # PDF 생성
├── pom.xml
└── README.md
```

<br>

## 📝 참고

- 본 도구는 **개인 학습 목적**으로 제작되었습니다
- 캡처한 악보는 개인 연습 용도로만 사용해주세요
- 영상 크리에이터 및 원곡의 저작권을 존중해주세요 🙏

<br>

---

<div align="center">
  Made with ♥ for Yorushika fans 🎵
</div>
