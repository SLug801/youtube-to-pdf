package com.sheetmusic;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.opencv.opencv_java;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

/**
 * 가로로 스크롤되는 TAB 악보 영상을 한 장의 긴 파노라마로 재구성한 뒤
 * 줄 단위로 잘라 PDF용 이미지로 저장한다.
 *
 * 영상 형태: 악보는 대부분 정지(재생 커서만 이동)하다가 줄 끝에서 스크롤되는 방식.
 * 따라서 "정지 구간은 무시하고, 실제 스크롤이 감지될 때만" 새 콘텐츠를 이어붙인다.
 *
 * 방식: 확정 화면(프런티어) 대비 전폭(全幅) 매칭.
 *  - "현재까지 확정된 화면(comFeat, ROI 전폭)"에 새 프레임을 정렬해 우측 이동량 dx를 구한다.
 *    템플릿을 현재 프레임 왼쪽에서 떼어 확정 화면 전체를 탐색하므로, 한 프레임에 화면의
 *    대부분이 바뀌는 "점프 스크롤"까지 dx를 측정한다(슬릿스캔의 ~17% 폭 한계 제거).
 *  - dx≈0(정지·재생바만 이동)이면 붙이지 않고, 실제 스크롤이면 새로 드러난 우측 dx만 이어붙여
 *    중복을 제거한다.
 *  - 겹침이 거의 없어 매칭이 실패하면 곧바로 버리지 않는다(누락 방지): 직전 샘플과 동일한
 *    "정지된 새 화면"으로 확인될 때만 화면 전체를 새 페이지로 이어붙인다. 전환/블러 프레임은
 *    안정될 때까지 보류한다.
 *  - 특징(featureImage)은 adaptiveThreshold로 표기만 추출 → 움직이는 반투명 배경을 억제하고
 *    가로 오선·잡티를 제거해 세로 획(마디선·숫자·기둥) 위주로 남겨 매칭 신뢰도를 높인다.
 */
public class FrameExtractor {

    static {
        try {
            Loader.load(opencv_java.class);
        } catch (UnsatisfiedLinkError e) {
            throw new RuntimeException("OpenCV 라이브러리 로드 실패", e);
        }
    }

    // ── 스캔/스티칭 파라미터 ────────────────────────────────────────────────
    private static final int    SCAN_FPS     = 20;      // (was 10) 초당 검사 프레임. 높일수록 큰 점프
                                                        //   스크롤을 작은 스텝 여러 개로 쪼개 봐서, 한 스텝에
                                                        //   들어가는 반복 패턴이 줄고 매칭 정확↑(중복·누락 동시 감소).
                                                        //   대신 처리 시간이 비례해 늘어난다.
    private static final double TPL_RATIO    = 0.15;    // (was 0.12) 매칭 템플릿 폭(ROI 폭 대비).
                                                        //   크게 잡을수록 템플릿이 더 고유해져
                                                        //   반복 패턴 오매칭(중복·이상 절단)이 준다.
                                                        //   대신 검출 가능한 최대 점프는 (roiW-tw)로 줄어듦.
    private static final int    TPL_INSET    = 0;       // 템플릿을 현재 프레임 왼쪽에서 떼는 위치
    private static final double MIN_SCORE    = 0.40;    // (was 0.35) 겹침 신뢰 임계. 높을수록 약하고
                                                        //   모호한 매칭으로 dx를 잘못 잡는 일이 준다.
    private static final double MARGIN       = 0.15;    // (was 0.12) peak−zero 마진. 높을수록 실제
                                                        //   스크롤만 채택 → 미세 false 스크롤(중복 슬라이버) 차단.
    private static final double SEED_REFRESH_SCORE = 0.70; // (was 0.80) 첫 스크롤 전 동일화면 판정(시드 갱신).
                                                           //   낮출수록 페이드인 중 더 또렷한 프레임으로 시드 교체
                                                           //   → 첫 화면이 하얗게 나오는 문제 완화.
    private static final double NEWPAGE_MAX_SCORE   = 0.30; // 이보다 매칭이 낮아야 "새 페이지" 후보
    private static final double STABLE_SCORE = 0.75;    // 정지(동일 화면) 판정 임계
    private static final int    MIN_SHIFT    = 4;       // (was 3) 이보다 작은 dx는 정지로 간주.
                                                        //   지터성 미세 중복을 차단(FPS↑로 스텝이 작아져 4 유지).
    private static final double CONTENT_MIN  = 0.004;   // 인트로(빈 화면) 판별 임계

    // [테스트] 영상의 [시작초, 끝초] 구간만 처리(빠른 실영상 확인용). 둘 다 0이면 전체.
    // 예: 2분대 장면 보려면 START=110, END=150.
    private static final double TEST_START_SECONDS = 0;
    private static final double TEST_END_SECONDS   = 0;

    // ── 2-밴드 합의(consensus) 검증 ──────────────────────────────────────────
    // 현재 프레임의 서로 다른 두 위치 밴드로 각각 dx를 재서, 둘이 일치할 때만 스크롤로 인정한다.
    // 강체 이동(진짜 스크롤)은 두 밴드 dx가 같지만, 빈 마디·반복 패턴의 주기적 오매칭은
    // 두 밴드가 서로 다른 오프셋에 꽂혀 불일치 → 거부(중복 append 차단).
    private static final double SECOND_BAND_RATIO = 0.30; // 둘째 밴드를 떼는 위치(ROI 폭 대비)
    private static final int    DX_AGREE_TOL      = 6;    // 두 밴드 dx 허용 오차(px) — 이내면 일치로 봄

    // ── 최종 출력 배경 노이즈 제거 ───────────────────────────────────────────
    private static final boolean DENOISE_OUTPUT  = true;  // 최종 결과물 노이즈 한 번 더 거르기 on/off
    private static final int     NOISE_FLOOR      = 45;   // (0~255) 대비 부스트 후 이 밝기 미만은
                                                          //   배경 텍스처로 보고 제거. 높일수록 더 빡세게.
    private static final int     NOISE_MIN_AREA   = 8;    // 이보다 작은 고립 덩어리(연결요소) 삭제.
                                                          //   면적 기준이라 긴 오선·기둥·숫자는 보존, 점 잡티만 제거.

    // ── 투명(TRANSPARENT) 모드 파라미터 ──────────────────────────────────────
    // 표기 극성: +1=밝은(흰) 표기만(tophat), -1=어두운(검정) 표기만(blackhat), 0=둘 다(max).
    // 표기가 흰색이면 +1로 둬야 어두운 배경(픽업·그림자)이 안 새어든다. 둘 다(0)는 배경 누출↑.
    private static final int     TRANSPARENT_POLARITY = +1;
    private static final int     TRANSPARENT_KERNEL  = 5;    // tophat/blackhat 커널. 작을수록 '가는 표기'만
                                                             //   남고 배경의 두꺼운 구조(연주자·악기)는 빠진다.
                                                             //   표기가 굵어 속이 비면 ↑, 배경이 새면 ↓.
    private static final double  TRANSPARENT_BOOST   = 1.5;  // 표기 대비 강화 배율(8U 포화). 배경이 같이
                                                             //   진해지면 ↓, 표기가 너무 옅으면 ↑.
    private static final int     TRANSPARENT_FILL    = 5;    // 출력에서 두꺼운 표기 '속 채우기' 닫힘 커널.
                                                             //   박자 빔·굵은 숫자 속이 비면 ↑(빔 두께만큼),
                                                             //   표기가 뭉개지면 ↓(0이면 끔). 검출엔 영향 없음.
    private static final boolean TRANSPARENT_BINARIZE = true; // 출력을 흑/백 이진화(흐린 회색 얼룩 제거→또렷).
    private static final int     TRANSPARENT_BIN_THRESH = 60;  // 0=Otsu 자동, >0=고정 임계(배경이 새면 30~60으로).
    // 강/약 임계 혼합(hysteresis): 강 임계로 '확실한 표기'를 잡고, 약 임계로 옅은 표기까지 본 뒤,
    // 약한 것 중 '강한 표기에 연결된 것만' 살린다 → 옅은 (5)는 살리고 외딴 배경 잡티는 버림.
    // 켜면 위 BIN_THRESH(단일 임계) 대신 아래 강/약 임계를 쓴다.
    private static final boolean TRANSPARENT_HYSTERESIS = true;
    private static final int     TRANSPARENT_T_HIGH = 55;  // 강 임계(확실한 표기). 배경 새면 ↑.
    private static final int     TRANSPARENT_T_LOW  = 20;  // 약 임계(옅은 (5) 포함). '(5)' 안 나오면 ↓, 배경 새면 ↑.
    // 큰 '솔리드 덩어리'(연주자·악기 누출) 제거: 면적이 크고 꽉 찬(채움밀도 높은) 덩어리만 삭제.
    // 표기는 가는 획(채움밀도 낮음)이라 보존된다. 단, 덩어리가 표기와 붙으면 같이 지워질 수 있음.
    private static final boolean TRANSPARENT_DEBLOB    = true;
    private static final int     TRANSPARENT_BLOB_AREA = 4000;   // 이 픽셀수 이상이고
    private static final double  TRANSPARENT_BLOB_DENS = 0.35;   // 채움밀도(면적/박스) 이 이상이면 덩어리로 보고 삭제
    private static final boolean TRANSPARENT_DESAT    = true; // 컬러 배경(높은 채도) 억제 on/off.
    private static final int     TRANSPARENT_SAT_MAX  = 60;   // (0~255) 이 채도 초과 화소는 컬러 배경으로
                                                             //   보고 표기에서 제외. 컬러 배경이 새면 ↓,
                                                             //   표기가 옅은 색이라 지워지면 ↑.

    public record RoiConfig(
            double topRatio, double bottomRatio,
            double leftRatio, double rightRatio) {

        public static RoiConfig defaultConfig() {
            return new RoiConfig(0.70, 1.00, 0.00, 1.00);
        }

        public static RoiConfig parse(String s) {
            String[] p = s.split(",");
            if (p.length != 4) throw new IllegalArgumentException("형식: top,bottom,left,right");
            return new RoiConfig(
                Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()),
                Double.parseDouble(p[2].trim()), Double.parseDouble(p[3].trim()));
        }

        @Override public String toString() {
            return String.format("상단%.0f%%~하단%.0f%%, 좌%.0f%%~우%.0f%%",
                topRatio*100, bottomRatio*100, leftRatio*100, rightRatio*100);
        }
    }

    private final RoiConfig roi;
    private final SheetMode mode;

    public FrameExtractor(RoiConfig roi) {
        this(roi, SheetMode.TRANSLUCENT);
    }

    public FrameExtractor(RoiConfig roi, SheetMode mode) {
        this.roi  = roi;
        this.mode = (mode != null) ? mode : SheetMode.TRANSLUCENT;
    }

    public List<Path> extract(Path videoPath, Path outDir) throws Exception {
        return extract(videoPath, outDir, null);
    }

    public List<Path> extract(Path videoPath, Path outDir, ProgressLogger logger) throws Exception {
        Files.createDirectories(outDir);

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toString())) {
            grabber.start();

            double fps      = grabber.getFrameRate();
            long   lengthUs = grabber.getLengthInTime();
            if (lengthUs <= 0) {
                int totalFrames = grabber.getLengthInFrames();
                lengthUs = (fps > 0 && totalFrames > 0)
                    ? (long)(totalFrames / fps * 1_000_000L) : 0;
            }
            double durationSec = lengthUs / 1_000_000.0;
            int    width   = grabber.getImageWidth();
            int    height  = grabber.getImageHeight();
            Rect   roiRect = makeRoiRect(width, height);

            int roiW    = roiRect.width;
            int roiH    = roiRect.height;
            int tw      = clamp((int)(roiW * TPL_RATIO), 8, roiW - 1);

            // 순차 디코딩하며 N프레임마다 한 번 검사한다(매 샘플 seek 제거 → 속도↑, 결과 동일).
            int frameSkip = Math.max(1, (int) Math.round((fps > 0 ? fps : SCAN_FPS) / SCAN_FPS));

            log(logger, "[시작] 해상도=%dx%d | FPS=%.1f | 길이=%.1fs | 모드=전폭매칭(검사%dfps, %d프레임마다)",
                width, height, fps, durationSec, SCAN_FPS, frameSkip);
            log(logger, "[설정] 악보모드=%s | ROI=%s | 템플릿=%dpx | 임계 match=%.2f stable=%.2f",
                mode.label, roi, tw, MIN_SCORE, STABLE_SCORE);

            List<Mat> colorStrips = new ArrayList<>();
            Mat   comFeat   = null;       // 파노라마 프런티어에 해당하는 "확정 화면"의 특징(roiW 폭)
            Mat   lastFeat  = null;       // 직전 샘플 프레임의 특징(정지/새 페이지 판정용)
            int   canvasW   = 0;          // 누적 파노라마 폭
            boolean started = false;
            boolean scrolled = false;     // 첫 스크롤 발생 여부(이전엔 시드 갱신 허용)
            int   scrollCnt = 0, pageCnt = 0, staticCnt = 0;   // 스크롤 / 새 페이지 / 정지
            int   rejectCnt = 0;          // 합의 불일치로 거부된 스크롤 후보 수
            int   secondInset = clamp((int)(roiW * SECOND_BAND_RATIO), tw, Math.max(tw, roiW - 2 * tw));
            int   reach2 = roiW - tw - secondInset;   // 둘째 밴드로 확인 가능한 최대 dx

            Java2DFrameConverter converter = new Java2DFrameConverter();
            long currentUs = 0, sampleIdx = 0, grabbedFrames = 0;
            long startMs   = System.currentTimeMillis();
            int  lastDx = 0; double lastScore = 0;

            if (TEST_START_SECONDS > 0) {     // 테스트: 지정 시작 지점으로 한 번만 seek
                grabber.setTimestamp((long) (TEST_START_SECONDS * 1_000_000L));
                log(logger, "[테스트] %.0f~%.0f초 구간만 처리", TEST_START_SECONDS, TEST_END_SECONDS);
            }

            while (true) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException("취소됨");

                Frame videoFrame = grabber.grabImage();
                if (videoFrame == null) break;
                if (grabbedFrames++ % frameSkip != 0) continue;   // 검사 대상이 아닌 프레임은 디코딩만 하고 건너뜀
                currentUs = grabber.getTimestamp();
                if (TEST_END_SECONDS > 0 && currentUs > TEST_END_SECONDS * 1_000_000L) break;  // 테스트: 끝초 도달

                Mat frame = frameToMat(videoFrame, converter);
                if (frame.empty()) { sampleIdx++; continue; }

                Mat roiColor = new Mat(frame, roiRect).clone();
                frame.release();
                Mat feat = featureImage(roiColor);

                if (!started) {
                    double cr = (double) Core.countNonZero(feat) / feat.total();
                    if (cr < CONTENT_MIN) {                 // 인트로(빈 화면) 스킵
                        feat.release(); roiColor.release();
                        sampleIdx++;
                        continue;
                    }
                    // 첫 콘텐츠 프레임: 화면 전체를 시드로
                    colorStrips.add(roiColor.clone());
                    comFeat  = feat.clone();
                    lastFeat = feat.clone();
                    canvasW  = roiW;
                    started  = true;
                    feat.release(); roiColor.release();
                    log(logger, "[콘텐츠 시작] t=%.1fs (시드 %dpx)", currentUs / 1_000_000.0, roiW);
                    sampleIdx++;
                    continue;
                }

                if (mode == SheetMode.OPAQUE) {
                    // ── 불투명: 페이지 스냅샷 ──
                    // 페이지가 정지하다 하드컷으로 여러 마디씩 넘어가고 겹침은 얇은 슬리버뿐이라,
                    // 서브마디 정합(dx 측정)은 마디 주기성에 속아 누락/중복이 난다(실측 확인).
                    // 대신 "확정 페이지와 충분히 달라지고(전환) + 직전 프레임과 같아짐(안정)"이면
                    // 새 페이지를 통째로 한 행으로 붙인다 — 경계의 얇은 슬리버만 겹치고 누락은 없다.
                    double simConf = matchOffset(comFeat,  feat, tw, TPL_INSET)[2]; // 확정페이지와 dx=0 상관
                    double simLast = matchOffset(lastFeat, feat, tw, TPL_INSET)[2]; // 직전프레임과 dx=0 상관
                    lastDx = 0; lastScore = simConf;
                    boolean changed = simConf < OPAQUE_SAME_PAGE;    // 확정 페이지와 달라짐 = 전환됨
                    boolean stable  = simLast >= OPAQUE_STABLE_PAGE; // 직전과 동일 = 전환 끝나 안정

                    if (changed && stable) {
                        colorStrips.add(roiColor.clone());
                        comFeat.release(); comFeat = feat.clone();
                        canvasW += roiW;
                        pageCnt++;
                        scrolled = true;
                    } else if (!scrolled && !changed) {
                        // 첫 페이지 전 정지 구간 → 또렷한 최신 프레임으로 시드 교체(흐린 첫 프레임 방지)
                        colorStrips.get(0).release();
                        colorStrips.set(0, roiColor.clone());
                        comFeat.release(); comFeat = feat.clone();
                        staticCnt++;
                    } else {
                        staticCnt++;   // 정지 또는 전환 중(미안정)
                    }
                } else {
                    // ── 반투명/투명: 연속 스크롤 누적(확정화면 comFeat 대비 dx 측정) ──
                    double[] m    = matchOffset(comFeat, feat, tw, TPL_INSET);
                    int    dx     = (int) m[0];
                    double score  = m[1];          // 최적 위치(dx) 상관
                    double zero   = m[2];          // dx=0(제로 시프트) 상관
                    double margin = score - zero;  // 스크롤 위치가 정지 위치보다 얼마나 더 잘 맞는가
                    lastDx = dx; lastScore = score;

                    // 실제 스크롤: dx 위치가 dx=0보다 뚜렷이(MARGIN) 더 잘 맞을 때만 채택.
                    boolean baseScroll = dx >= MIN_SHIFT && dx <= roiW - tw
                                      && score >= MIN_SCORE && margin >= MARGIN;

                    // 2-밴드 합의: 다른 위치 밴드로도 같은 dx가 나와야 진짜 스크롤로 인정(주기 오매칭 차단).
                    boolean isScroll = baseScroll;
                    if (baseScroll && dx <= reach2) {
                        double[] m2 = matchOffset(comFeat, feat, tw, secondInset);
                        boolean confidentDisagree = m2[1] >= MIN_SCORE
                                                 && Math.abs(dx - (int) m2[0]) > DX_AGREE_TOL;
                        if (confidentDisagree) { isScroll = false; rejectCnt++; }
                    }

                    if (isScroll) {
                        colorStrips.add(new Mat(roiColor, new Rect(roiW - dx, 0, dx, roiH)).clone());
                        comFeat.release(); comFeat = feat.clone();
                        canvasW += dx;
                        scrollCnt++;
                        scrolled = true;
                    } else if (!scrolled && zero >= SEED_REFRESH_SCORE) {
                        // 첫 스크롤 전 동일 화면(인트로) → 또렷한 최신 프레임으로 시드 교체.
                        colorStrips.get(0).release();
                        colorStrips.set(0, roiColor.clone());
                        comFeat.release(); comFeat = feat.clone();
                        staticCnt++;
                    } else if (score < NEWPAGE_MAX_SCORE) {
                        // 확정 화면과 겹침을 못 찾음 → 완전히 새 화면일 수 있음(누락 방지).
                        double[] s = matchOffset(lastFeat, feat, tw, TPL_INSET);
                        if (s[2] >= STABLE_SCORE) {     // 직전 샘플과 dx=0에서 일치 = 정지된 새 화면
                            colorStrips.add(roiColor.clone());
                            comFeat.release(); comFeat = feat.clone();
                            canvasW += roiW;
                            pageCnt++;
                            scrolled = true;
                        } else {
                            staticCnt++;   // 전환/블러 프레임 → 안정될 때까지 보류
                        }
                    } else {
                        staticCnt++;       // 정지(재생바만 이동) 또는 주기적 오매칭
                    }
                }

                lastFeat.release(); lastFeat = feat.clone();
                feat.release(); roiColor.release();

                if (sampleIdx > 0 && sampleIdx % (SCAN_FPS * 8) == 0) {
                    double pct     = lengthUs > 0 ? (double) currentUs / lengthUs * 100 : -1;
                    long   elapsed = (System.currentTimeMillis() - startMs) / 1000;
                    log(logger, "  진행 %.0f%% (%ds) 폭=%dpx | dx=%d score=%.2f | 스크롤%d 페이지%d 정지%d 합의거부%d",
                        Math.max(pct, 0), elapsed, canvasW, lastDx, lastScore,
                        scrollCnt, pageCnt, staticCnt, rejectCnt);
                }

                sampleIdx++;
            }

            if (comFeat  != null) comFeat.release();
            if (lastFeat != null) lastFeat.release();

            if (colorStrips.isEmpty()) {
                log(logger, "[경고] 콘텐츠를 찾지 못했습니다.");
                return new ArrayList<>();
            }

            Mat panorama = new Mat();
            Core.hconcat(colorStrips, panorama);
            for (Mat s : colorStrips) s.release();
            log(logger, "[파노라마] 폭=%dpx 높이=%dpx | 스크롤%d 페이지%d 정지%d 합의거부%d",
                panorama.cols(), panorama.rows(), scrollCnt, pageCnt, staticCnt, rejectCnt);

            Mat cleaned = cleanForOutput(panorama);   // 반투명/배경 제거 → 흰 종이+검은 표기
            panorama.release();
            List<Path> saved = sliceAndSave(cleaned, roiW, outDir, logger);
            cleaned.release();
            log(logger, "[완료] 총 %d줄 생성", saved.size());
            return saved;
        }
    }

    /**
     * 기준 화면(ref) 안에서 현재 프레임(cur)의 왼쪽 밴드를 찾아 우측 이동량 dx와 신뢰도를 반환한다.
     * 템플릿을 cur의 왼쪽(inset)에서 떼고 ref 전체를 탐색하므로, 화면 폭에 가까운 큰 점프
     * 스크롤까지 측정 가능하다(슬릿스캔의 폭 한계 없음). 단, 검출 가능한 최소 겹침은 템플릿 폭(tw).
     *
     * @return {dx, peakScore, zeroScore}. dx ≥ 0 이면 cur이 ref보다 오른쪽으로 dx만큼 이동(스크롤).
     *         zeroScore는 dx=0(제로 시프트)에서의 상관 — 높으면 화면이 안 움직인 것(정지).
     */
    private double[] matchOffset(Mat ref, Mat cur, int tw, int inset) {
        int h  = cur.rows();
        int cw = cur.cols();
        int tx = clamp(inset, 0, Math.max(0, cw - tw));
        Mat tpl = new Mat(cur, new Rect(tx, 0, tw, h));
        Mat res = new Mat();
        Imgproc.matchTemplate(ref, tpl, res, Imgproc.TM_CCOEFF_NORMED);
        Core.MinMaxLocResult mmr = Core.minMaxLoc(res);
        double zero = res.get(0, tx)[0];     // loc=tx ↔ dx=0
        tpl.release(); res.release();
        return new double[]{ (int) mmr.maxLoc.x - tx, mmr.maxVal, zero };
    }

    /**
     * 매칭/콘텐츠 판별용 특징 이미지.
     * 모폴로지 블랙햇으로 "(반투명) 패널 위 어두운 표기(숫자·마디선·기둥)"만 추출한다.
     * 블랙햇은 국소 대비 기반이라 전역 밝기/투명도에 무관 — 반투명이 강하거나(흐림),
     * 패널 없이 투명하거나, 너무 밝은 경우까지 표기를 안정적으로 살린다(adaptiveThreshold보다
     * 배경 잡음이 훨씬 적음, 실측 검증됨). 가로 오선은 제거해 세로 특징 위주로 남긴다.
     */
    private Mat featureImage(Mat roiColor) {
        if (mode == SheetMode.TRANSPARENT) return featureImageTransparent(roiColor);
        if (mode == SheetMode.OPAQUE)      return featureImageOpaque(roiColor);
        // ── 이하 반투명(TRANSLUCENT) 기존 로직 (그대로 보존) ──
        Mat gray = new Mat();
        Imgproc.cvtColor(roiColor, gray, Imgproc.COLOR_BGR2GRAY);
        // 어두운 패널이면 반전 → 표기를 항상 "밝은 배경 위 어두운 선"으로 정규화
        if (Core.mean(gray).val[0] < 100) Core.bitwise_not(gray, gray);

        Mat k  = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9, 9));
        Mat bh = new Mat();
        Imgproc.morphologyEx(gray, bh, Imgproc.MORPH_BLACKHAT, k);
        k.release(); gray.release();

        Mat bin = new Mat();
        Imgproc.threshold(bh, bin, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
        bh.release();

        // 긴 가로 오선 제거(매칭은 세로 획이 핵심)
        int kw = Math.max(15, bin.cols() / 3);
        Mat hk    = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(kw, 1));
        Mat lines = new Mat();
        Imgproc.morphologyEx(bin, lines, Imgproc.MORPH_OPEN, hk);
        Core.subtract(bin, lines, bin);
        hk.release(); lines.release();
        return bin;
    }

    /** 파노라마를 chunkW 단위로 잘라 PDF용 이미지로 저장. 마지막 좁은 조각은 흰 여백으로 패딩. */
    private List<Path> sliceAndSave(Mat panorama, int chunkW, Path outDir, ProgressLogger logger)
            throws Exception {
        int Pw = panorama.cols(), Ph = panorama.rows();
        List<Path> saved = new ArrayList<>();
        int idx = 0;

        for (int x = 0; x < Pw; x += chunkW) {
            int w = Math.min(chunkW, Pw - x);
            if (w < 40) break;

            Mat out = new Mat(panorama, new Rect(x, 0, w, Ph)).clone();

            if (w < chunkW) {                          // 마지막 조각 패딩(과대 확대 방지)
                Mat padded = new Mat(Ph, chunkW, out.type(), new Scalar(255, 255, 255));
                out.copyTo(padded.submat(new Rect(0, 0, w, Ph)));
                out.release();
                out = padded;
            }

            Path p = outDir.resolve(String.format("frame_%04d.jpg", idx++));
            Imgcodecs.imwrite(p.toString(), out);
            out.release();
            saved.add(p);
            log(logger, "FRAME_SAVED:%s", p.toAbsolutePath());
        }
        return saved;
    }

    /**
     * 출력용 배경 제거: 블랙햇으로 어두운 표기(오선 포함)만 추출해 "흰 종이 + 검은 표기"로 만든다.
     * 반투명 배경(뮤비/연주자)·과밝은 패널을 전역 밝기와 무관하게 제거한다(실측 검증됨).
     * 매칭용 featureImage와 달리 가로 오선은 보존한다(악보의 일부).
     */
    private Mat cleanForOutput(Mat panoBGR) {
        if (mode == SheetMode.TRANSPARENT) return cleanForOutputTransparent(panoBGR);
        if (mode == SheetMode.OPAQUE)      return cleanForOutputOpaque(panoBGR);
        // ── 이하 반투명(TRANSLUCENT) 기존 로직 (그대로 보존) ──
        Mat gray = new Mat();
        Imgproc.cvtColor(panoBGR, gray, Imgproc.COLOR_BGR2GRAY);
        if (Core.mean(gray).val[0] < 100) Core.bitwise_not(gray, gray);

        // 대비 보정(CLAHE): fade-in 등으로 흐린 구간의 표기도 출력에서 살린다.
        Mat eq = new Mat();
        Imgproc.createCLAHE(2.0, new Size(8, 8)).apply(gray, eq);
        gray.release();

        Mat k  = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9, 9));
        Mat bh = new Mat();
        Imgproc.morphologyEx(eq, bh, Imgproc.MORPH_BLACKHAT, k);
        k.release(); eq.release();

        Core.multiply(bh, new Scalar(3.0), bh);          // 옅은 표기 대비 강화(8U 포화)
        if (DENOISE_OUTPUT) denoiseMarks(bh);            // 배경 텍스처·점 잡티 한 번 더 제거
        Mat inv = new Mat();
        Core.bitwise_not(bh, inv);                        // 255-bh → 흰 배경 + 검은 표기
        bh.release();

        Mat out = new Mat();
        Imgproc.cvtColor(inv, out, Imgproc.COLOR_GRAY2BGR);
        inv.release();
        return out;
    }

    // ── 불투명(OPAQUE) 모드 ──────────────────────────────────────────────────
    // 흰 배경 + 검정 악보(이미 깨끗한 스캔/PDF형). 단순 이진화로 또렷하게 만든다.
    // 반투명/투명과 달리 배경 억제용 모폴로지가 거의 필요 없다.
    private static final int OPAQUE_BLOCK = 31;   // adaptiveThreshold 블록 크기(홀수). 클수록 큰 음영에 둔감.
    private static final int OPAQUE_C     = 12;   // 국소 배경 대비 잉크 판정 여유. 클수록 옅은 회색·잡티 덜 잡힘.
    // 페이지 스냅샷(불투명): 확정 페이지와의 dx=0 상관이 SAME_PAGE 미만이면 '전환됨',
    // 직전 프레임과의 상관이 STABLE_PAGE 이상이면 '안정(전환 끝남)'으로 보고 새 행을 붙인다.
    // simConf 이중분포: ~0.70은 같은 페이지에서 재생 하이라이트/플레이헤드만 이동, ~0.50은 진짜 플립.
    // 둘 사이(0.62)로 갈라 하이라이트 이동은 무시하고 실제 페이지 전환만 새 행으로 커밋한다.
    private static final double OPAQUE_SAME_PAGE   = 0.62; // 이 미만이면 확정 페이지와 다른 페이지(플립)
    private static final double OPAQUE_STABLE_PAGE = 0.90; // 이 이상이면 직전과 같아 안정(미안정 전환 배제)

    /**
     * 흰 배경 + 검정 악보를 "표기=흰(255) / 배경=검(0)" 이진 마스크로 만든다.
     * 평균 밝기가 낮으면(검은 배경에 흰 악보) 자동 반전해 항상 동일 극성으로 정규화.
     *
     * 전역 Otsu는 가장 진한 음표만 남기고 더 옅은 회색 오선·TAB 프렛 숫자를 버린다.
     * 그래서 adaptiveThreshold를 써 "국소 종이 배경보다 OPAQUE_C 이상 어두우면 잉크"로 판정 →
     * 진한 음표와 옅은 회색 숫자·오선을 모두 살린다. 재생위치 하이라이트(옅은 노랑/형광)는
     * 종이와 밝기 차가 작아 마킹되지 않는다.
     */
    private Mat binarizeOpaque(Mat colorSrc) {
        Mat gray = new Mat();
        Imgproc.cvtColor(colorSrc, gray, Imgproc.COLOR_BGR2GRAY);
        if (Core.mean(gray).val[0] < 110) Core.bitwise_not(gray, gray);   // 어두운 스캔 정규화
        Mat bin = new Mat();
        Imgproc.adaptiveThreshold(gray, bin, 255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV,
                OPAQUE_BLOCK, OPAQUE_C);
        gray.release();
        return bin;
    }

    /** 불투명 매칭 특징: 이진화 후 긴 가로 오선 제거(세로 획 위주). */
    private Mat featureImageOpaque(Mat roiColor) {
        Mat bin = binarizeOpaque(roiColor);
        int kw = Math.max(15, bin.cols() / 3);
        Mat hk    = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(kw, 1));
        Mat lines = new Mat();
        Imgproc.morphologyEx(bin, lines, Imgproc.MORPH_OPEN, hk);
        Core.subtract(bin, lines, bin);
        hk.release(); lines.release();
        return bin;
    }

    /** 불투명 출력: 이진화 → 작은 점 잡티 제거 → 반전(흰 종이+검은 표기). */
    private Mat cleanForOutputOpaque(Mat panoBGR) {
        Mat bin = binarizeOpaque(panoBGR);
        if (DENOISE_OUTPUT) denoiseMarks(bin);   // 점 잡티만 제거(오선·숫자는 보존)
        Mat inv = new Mat();
        Core.bitwise_not(bin, inv);              // 255-bin → 흰 배경 + 검은 표기
        bin.release();
        Mat out = new Mat();
        Imgproc.cvtColor(inv, out, Imgproc.COLOR_GRAY2BGR);
        inv.release();
        return out;
    }

    /**
     * 출력 표기(marks: 밝을수록 표기)에서 배경 노이즈를 한 번 더 제거한다.
     *  1) 밝기 바닥값(NOISE_FLOOR) 미만 → 0 : 반투명 배경이 옅게 비친 저대비 텍스처 제거.
     *  2) 작은 고립 덩어리(NOISE_MIN_AREA 미만) 제거 : 점 잡티만 삭제하고, 면적이 큰
     *     오선·기둥·숫자는 보존(median 방식과 달리 가는 선을 지우지 않음).
     */
    private void denoiseMarks(Mat marks) {
        // 1) 약한 배경 텍스처 제거
        Imgproc.threshold(marks, marks, NOISE_FLOOR, 0, Imgproc.THRESH_TOZERO);
        if (NOISE_MIN_AREA <= 1) return;

        // 2) 작은 고립 덩어리 제거(면적 기준)
        Mat bin = new Mat();
        Imgproc.threshold(marks, bin, 0, 255, Imgproc.THRESH_BINARY);
        Mat labels = new Mat(), stats = new Mat(), cent = new Mat();
        int n = Imgproc.connectedComponentsWithStats(bin, labels, stats, cent, 8, CvType.CV_32S);
        bin.release(); cent.release();

        if (n > 1) {
            boolean[] keep = new boolean[n];          // keep[0]=배경은 false 유지
            for (int i = 1; i < n; i++)
                keep[i] = stats.get(i, Imgproc.CC_STAT_AREA)[0] >= NOISE_MIN_AREA;

            int total = (int) labels.total();
            int[] lab = new int[total];
            labels.get(0, 0, lab);
            byte[] mask = new byte[total];
            for (int p = 0; p < total; p++)
                if (keep[lab[p]]) mask[p] = (byte) 0xFF;

            Mat keepMask = new Mat(labels.rows(), labels.cols(), CvType.CV_8U);
            keepMask.put(0, 0, mask);
            Mat cleaned = Mat.zeros(marks.size(), marks.type());
            marks.copyTo(cleaned, keepMask);
            cleaned.copyTo(marks);
            keepMask.release(); cleaned.release();
        }
        labels.release(); stats.release();
    }

    /**
     * 이력(hysteresis) 이중 임계. marks(밝을수록 표기)에서
     *  - 강 임계(tHigh) 이상 = '확실한 표기'
     *  - 약 임계(tLow) 이상 = '후보'(옅은 표기 + 배경 잡음)
     * 후보 연결요소 중 '확실한 표기'를 하나라도 포함한 것만 살린다.
     * → 옅어도 진짜 표기(강한 표기와 연결)는 보존, 외딴 배경 잡티는 제거.
     */
    /**
     * 이진 영상에서 '크고 꽉 찬 덩어리'(연주자·악기 누출)를 제거한다.
     * 면적 ≥ minArea 이고 채움밀도(면적/바운딩박스) ≥ minDensity 인 연결요소만 삭제.
     * 표기(가는 획·숫자)는 채움밀도가 낮아 보존된다.
     */
    private void removeLargeBlobs(Mat bin, int minArea, double minDensity) {
        Mat labels = new Mat(), stats = new Mat(), cent = new Mat();
        int n = Imgproc.connectedComponentsWithStats(bin, labels, stats, cent, 8, CvType.CV_32S);
        cent.release();
        if (n <= 1) { labels.release(); stats.release(); return; }

        boolean[] drop = new boolean[n];
        for (int i = 1; i < n; i++) {
            double area = stats.get(i, Imgproc.CC_STAT_AREA)[0];
            double w    = stats.get(i, Imgproc.CC_STAT_WIDTH)[0];
            double h    = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0];
            double dens = area / Math.max(1.0, w * h);
            if (area >= minArea && dens >= minDensity) drop[i] = true;
        }
        int total = (int) labels.total();
        int[]  lab  = new int[total]; labels.get(0, 0, lab);
        byte[] data = new byte[total]; bin.get(0, 0, data);
        for (int p = 0; p < total; p++) if (drop[lab[p]]) data[p] = 0;
        bin.put(0, 0, data);
        labels.release(); stats.release();
    }

    private Mat hysteresis(Mat marks, int tLow, int tHigh) {
        Mat weak = new Mat(), strong = new Mat();
        Imgproc.threshold(marks, weak,   tLow,  255, Imgproc.THRESH_BINARY);
        Imgproc.threshold(marks, strong, tHigh, 255, Imgproc.THRESH_BINARY);

        Mat labels = new Mat(), stats = new Mat(), cent = new Mat();
        int n = Imgproc.connectedComponentsWithStats(weak, labels, stats, cent, 8, CvType.CV_32S);
        stats.release(); cent.release(); weak.release();

        Mat res = Mat.zeros(marks.size(), CvType.CV_8U);
        if (n > 1) {
            int total = (int) labels.total();
            int[]  lab = new int[total];  labels.get(0, 0, lab);
            byte[] str = new byte[total]; strong.get(0, 0, str);
            boolean[] keep = new boolean[n];               // 강한 표기를 포함한 라벨 표시
            for (int p = 0; p < total; p++)
                if (str[p] != 0 && lab[p] > 0) keep[lab[p]] = true;
            byte[] out = new byte[total];
            for (int p = 0; p < total; p++)
                if (keep[lab[p]]) out[p] = (byte) 0xFF;
            res.put(0, 0, out);
        }
        labels.release(); strong.release();
        return res;
    }

    // ── 투명(TRANSPARENT) 모드 전용 ───────────────────────────────────────────
    // 패널 없이 영상 위에 악보가 직접 올라가고, 표기 색이 흰색일 수도(밝은 표기) 검정일 수도
    // (어두운 표기) 있다. tophat=밝은 가는 구조, blackhat=어두운 가는 구조를 각각 뽑아 합치면,
    // 표기 색 극성과 무관하게 표기만 남기고 완만한 배경은 억제된다.

    /**
     * 투명 모드 표기 강도 맵(밝을수록 표기). 색 극성·컬러 배경에 강인하게:
     *  - tophat(밝은 가는 구조)+blackhat(어두운 가는 구조)의 max → 흰/검 표기 모두 추출, 완만한 배경 억제
     *  - (옵션) 채도 높은 화소 억제 → 컬러 영상 배경(악기·커튼·진행바) 제거. 무채색 표기는 보존.
     */
    private Mat transparentMarks(Mat colorSrc, boolean clahe) {
        Mat gray = new Mat();
        Imgproc.cvtColor(colorSrc, gray, Imgproc.COLOR_BGR2GRAY);
        if (clahe) {
            Mat eq = new Mat();
            Imgproc.createCLAHE(2.0, new Size(8, 8)).apply(gray, eq);
            gray.release(); gray = eq;
        }

        int ks = Math.max(3, TRANSPARENT_KERNEL);
        Mat k  = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(ks, ks));
        Mat marks = new Mat();
        if (TRANSPARENT_POLARITY > 0) {
            Imgproc.morphologyEx(gray, marks, Imgproc.MORPH_TOPHAT, k);    // 밝은(흰) 표기만
        } else if (TRANSPARENT_POLARITY < 0) {
            Imgproc.morphologyEx(gray, marks, Imgproc.MORPH_BLACKHAT, k);  // 어두운(검정) 표기만
        } else {
            Mat top = new Mat(), bot = new Mat();
            Imgproc.morphologyEx(gray, top, Imgproc.MORPH_TOPHAT,   k);
            Imgproc.morphologyEx(gray, bot, Imgproc.MORPH_BLACKHAT, k);
            Core.max(top, bot, marks);                                    // 둘 다(극성 모를 때)
            top.release(); bot.release();
        }
        k.release(); gray.release();

        if (TRANSPARENT_DESAT) {                                      // 컬러 배경(높은 채도) 억제
            Mat hsv = new Mat();
            Imgproc.cvtColor(colorSrc, hsv, Imgproc.COLOR_BGR2HSV);
            java.util.List<Mat> ch = new java.util.ArrayList<>();
            Core.split(hsv, ch);
            Mat lowSat = new Mat();
            Imgproc.threshold(ch.get(1), lowSat, TRANSPARENT_SAT_MAX, 255, Imgproc.THRESH_BINARY_INV);
            Core.min(marks, lowSat, marks);   // 채도 높은(=컬러) 화소를 0으로 깎음
            hsv.release(); for (Mat c : ch) c.release(); lowSat.release();
        }
        return marks;
    }

    // [테스트 전용] 단일 프레임 디버그 훅 — TransparentTest에서 호출. 앱 동작과 무관.
    public Mat debugTransparentOutput(Mat colorFrame)  { return cleanForOutputTransparent(colorFrame); }
    public Mat debugTransparentFeature(Mat colorFrame) { return featureImageTransparent(colorFrame); }

    // [테스트 전용] 불투명 모드 디버그 훅 — OpaqueFrameTest에서 호출. 앱 동작과 무관.
    public Mat debugOpaqueOutput(Mat colorFrame)  { return cleanForOutputOpaque(colorFrame); }
    public Mat debugOpaqueFeature(Mat colorFrame) { return featureImageOpaque(colorFrame); }

    /** [테스트 전용] 영상의 positionRatio 지점 한 프레임을 풀프레임 Mat(BGR)으로 반환. */
    public static Mat captureFrameMat(Path videoPath, double positionRatio) throws Exception {
        Loader.load(opencv_java.class);
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toString())) {
            grabber.start();
            long lengthUs = grabber.getLengthInTime();
            if (lengthUs <= 0) lengthUs = 30_000_000L;
            grabber.setTimestamp((long)(lengthUs * positionRatio));
            Frame f = grabber.grabImage();
            if (f == null) throw new IllegalStateException("프레임 읽기 실패 @" + positionRatio);
            return frameToMat(f, new Java2DFrameConverter());
        }
    }

    /** 투명 모드 매칭용 특징(밝은/어두운 표기 모두, 세로 획 위주). */
    private Mat featureImageTransparent(Mat roiColor) {
        Mat marks = transparentMarks(roiColor, false);

        Mat bin = new Mat();
        Imgproc.threshold(marks, bin, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
        marks.release();

        // 긴 가로 오선 제거(매칭은 세로 획이 핵심) — 반투명과 동일
        int kw = Math.max(15, bin.cols() / 3);
        Mat hk    = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(kw, 1));
        Mat lines = new Mat();
        Imgproc.morphologyEx(bin, lines, Imgproc.MORPH_OPEN, hk);
        Core.subtract(bin, lines, bin);
        hk.release(); lines.release();
        return bin;
    }

    /** 투명 모드 출력 정리: 밝은/어두운 표기를 모두 흰 종이+검은 표기로. */
    private Mat cleanForOutputTransparent(Mat panoBGR) {
        Mat marks = transparentMarks(panoBGR, true);                  // CLAHE 대비 보정 포함

        Core.multiply(marks, new Scalar(TRANSPARENT_BOOST), marks);   // 옅은 표기 대비 강화

        if (TRANSPARENT_BINARIZE) {                                   // 흑/백 이진화(흐린 회색 얼룩 제거)
            if (TRANSPARENT_HYSTERESIS) {
                Mat hyst = hysteresis(marks, TRANSPARENT_T_LOW, TRANSPARENT_T_HIGH);
                marks.release(); marks = hyst;                       // 강/약 임계 혼합(연결된 약한 표기만)
            } else if (TRANSPARENT_BIN_THRESH > 0) {
                Imgproc.threshold(marks, marks, TRANSPARENT_BIN_THRESH, 255, Imgproc.THRESH_BINARY);
            } else {
                Imgproc.threshold(marks, marks, 0, 255, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU);
            }
        }

        if (TRANSPARENT_DEBLOB)                                       // 큰 솔리드 덩어리(연주자·악기) 제거
            removeLargeBlobs(marks, TRANSPARENT_BLOB_AREA, TRANSPARENT_BLOB_DENS);

        if (TRANSPARENT_FILL >= 2) {                                  // 두꺼운 표기 속 채우기(닫힘)
            Mat fk = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, new Size(TRANSPARENT_FILL, TRANSPARENT_FILL));
            Imgproc.morphologyEx(marks, marks, Imgproc.MORPH_CLOSE, fk);
            fk.release();
        }
        if (DENOISE_OUTPUT) denoiseMarks(marks);                      // 배경 텍스처·점 잡티 제거

        Mat inv = new Mat();
        Core.bitwise_not(marks, inv);                                 // 흰 배경 + 검은 표기
        marks.release();

        Mat out = new Mat();
        Imgproc.cvtColor(inv, out, Imgproc.COLOR_GRAY2BGR);
        inv.release();
        return out;
    }

    // ── 유틸 ─────────────────────────────────────────────────────────────────

    private static Mat frameToMat(Frame frame, Java2DFrameConverter converter) {
        BufferedImage bimg = converter.convert(frame);
        if (bimg == null) return new Mat();

        if (bimg.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            BufferedImage bgr = new BufferedImage(
                bimg.getWidth(), bimg.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g2 = bgr.createGraphics();
            g2.drawImage(bimg, 0, 0, null);
            g2.dispose();
            bimg = bgr;
        }

        byte[] data = ((DataBufferByte) bimg.getRaster().getDataBuffer()).getData();
        Mat mat = new Mat(bimg.getHeight(), bimg.getWidth(), CvType.CV_8UC3);
        mat.put(0, 0, data);
        return mat;
    }

    private Rect makeRoiRect(int w, int h) {
        int y1 = clamp((int)(h * roi.topRatio()),    0, h - 1);
        int y2 = clamp((int)(h * roi.bottomRatio()), y1 + 1, h);
        int x1 = clamp((int)(w * roi.leftRatio()),   0, w - 1);
        int x2 = clamp((int)(w * roi.rightRatio()),  x1 + 1, w);
        return new Rect(x1, y1, x2 - x1, y2 - y1);
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    public static BufferedImage captureFrame(Path videoPath, double positionRatio, RoiConfig roi) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toString())) {
            grabber.start();

            long lengthUs = grabber.getLengthInTime();
            if (lengthUs <= 0) {
                int totalFrames = grabber.getLengthInFrames();
                double fps = grabber.getFrameRate();
                lengthUs = (fps > 0 && totalFrames > 0)
                    ? (long)(totalFrames / fps * 1_000_000L) : 30_000_000L;
            }
            long posUs = (long)(lengthUs * positionRatio);
            grabber.setTimestamp(posUs);
            Frame videoFrame = grabber.grabImage();
            if (videoFrame == null) throw new IllegalStateException("프레임 읽기 실패");

            Mat frame = frameToMat(videoFrame, new Java2DFrameConverter());

            MatOfByte buf = new MatOfByte();
            Imgcodecs.imencode(".png", frame, buf);
            BufferedImage img;
            try (ByteArrayInputStream in = new ByteArrayInputStream(buf.toArray())) {
                img = ImageIO.read(in);
            }
            frame.release();
            return img;
        }
    }

    private static void log(ProgressLogger logger, String fmt, Object... args) {
        String msg = String.format(fmt, args);
        if (logger != null) logger.log(msg); else System.out.println(msg);
    }
}
