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
import org.opencv.imgcodecs.Imgcodecs;

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
 *
 * 픽셀 연산(특징 추출·이진화·매칭·출력 정리·노이즈 제거)은 {@link SheetImageOps}로 분리했고,
 * 이 클래스는 프레임 샘플링과 누적 스티칭 상태 전이(스크롤/페이지/정지 판정)만 담당한다.
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
    private static final int    SCAN_FPS_OPAQUE = 6;    // 불투명(페이지 넘김)은 페이지가 몇 초씩 정지하므로
                                                        //   초당 6회만 검사해도 전환을 놓치지 않는다. 매칭 연산을
                                                        //   1/3 이하로 줄여 속도↑(2샘플=약 0.33s면 새 페이지 확정).
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
    private static final double NEWPAGE_OVERLAP_SCORE = 0.55; // 새 페이지 통째 붙이기 전, raw 회색조로 확정 화면과
                                                            //   겹침 재확인하는 임계. 이 이상이면 겹침으로 보고 trim.
                                                            //   feature가 반복 패턴에서 실패해 생긴 통째-중복을 잡음.
    private static final double STABLE_SCORE = 0.75;    // 정지(동일 화면) 판정 임계
    private static final int    MIN_SHIFT    = 4;       // (was 3) 이보다 작은 dx는 정지로 간주.
                                                        //   지터성 미세 중복을 차단(FPS↑로 스텝이 작아져 4 유지).
    private static final double CONTENT_MIN  = 0.004;   // 인트로(빈 화면) 판별 임계

    // 페이지 스냅샷(불투명): 확정 페이지와의 dx=0 상관이 SAME_PAGE 미만이면 '전환됨',
    // 직전 프레임과의 상관이 STABLE_PAGE 이상이면 '안정(전환 끝남)'으로 보고 새 행을 붙인다.
    // simConf 이중분포: ~0.70은 같은 페이지에서 재생 하이라이트/플레이헤드만 이동, ~0.50은 진짜 플립.
    // 둘 사이(0.62)로 갈라 하이라이트 이동은 무시하고 실제 페이지 전환만 새 행으로 커밋한다.
    private static final double OPAQUE_SAME_PAGE   = 0.62; // 이 미만이면 확정 페이지와 다른 페이지(플립)
    private static final double OPAQUE_STABLE_PAGE = 0.90; // 이 이상이면 직전과 같아 안정(미안정 전환 배제)
    // [A안] 새 페이지를 통째 붙이기 전, 확정 페이지와 겹친 만큼만 잘라 중복을 없앤다.
    //   누락이 더 치명적이므로 반투명(MIN_SCORE 0.40)보다 빡빡하게 — "확실할 때만" trim, 아니면 통째.
    private static final double OPAQUE_TRIM_SCORE  = 0.60; // 겹침 신뢰 임계(이 미만이면 못 믿어 통째 붙임)

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
    private final SheetImageOps ops;

    public FrameExtractor(RoiConfig roi) {
        this(roi, SheetMode.TRANSLUCENT);
    }

    public FrameExtractor(RoiConfig roi, SheetMode mode) {
        this.roi  = roi;
        this.mode = (mode != null) ? mode : SheetMode.TRANSLUCENT;
        this.ops  = new SheetImageOps(this.mode);
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
            // 불투명은 페이지가 오래 정지하므로 더 낮은 검사 FPS로 매칭 연산을 줄인다.
            final int scanFps = (mode == SheetMode.OPAQUE) ? SCAN_FPS_OPAQUE : SCAN_FPS;
            int frameSkip = Math.max(1, (int) Math.round((fps > 0 ? fps : scanFps) / (double) scanFps));

            log(logger, "[시작] 해상도=%dx%d | FPS=%.1f | 길이=%.1fs | 모드=전폭매칭(검사%dfps, %d프레임마다)",
                width, height, fps, durationSec, scanFps, frameSkip);
            log(logger, "[설정] 악보모드=%s | ROI=%s | 템플릿=%dpx | 임계 match=%.2f stable=%.2f",
                mode.label, roi, tw, MIN_SCORE, STABLE_SCORE);

            List<Mat> colorStrips = new ArrayList<>();
            Mat   comFeat   = null;       // 파노라마 프런티어에 해당하는 "확정 화면"의 특징(roiW 폭)
            Mat   comColor  = null;       // 같은 확정 화면의 컬러본(반복 패턴에서 raw 겹침 재확인용)
            Mat   lastFeat  = null;       // 직전 샘플 프레임의 특징(정지/새 페이지 판정용)
            int   canvasW   = 0;          // 누적 파노라마 폭
            boolean started = false;
            boolean scrolled = false;     // 첫 스크롤 발생 여부(이전엔 시드 갱신 허용)
            int   scrollCnt = 0, pageCnt = 0, staticCnt = 0;   // 스크롤 / 새 페이지 / 정지
            int   rejectCnt = 0;          // 합의 불일치로 거부된 스크롤 후보 수
            int   trimCnt   = 0;          // [불투명] 확정 페이지와 겹친 만큼 잘라 중복 제거한 횟수
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
                Mat feat = ops.featureImage(roiColor);

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
                    comColor = roiColor.clone();
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
                    double simConf = ops.matchOffset(comFeat,  feat, tw, TPL_INSET)[2]; // 확정페이지와 dx=0 상관
                    double simLast = ops.matchOffset(lastFeat, feat, tw, TPL_INSET)[2]; // 직전프레임과 dx=0 상관
                    lastDx = 0; lastScore = simConf;
                    boolean changed = simConf < OPAQUE_SAME_PAGE;    // 확정 페이지와 달라짐 = 전환됨
                    boolean stable  = simLast >= OPAQUE_STABLE_PAGE; // 직전과 동일 = 전환 끝나 안정

                    if (changed && stable) {
                        // 새 페이지 확정. 기본은 통째 붙이기(누락 0). 단, 확정 페이지와의 겹침이
                        // "확실"할 때만 겹친 만큼 잘라 중복을 없앤다(애매하면 통째 = 중복 감수).
                        double[] m  = ops.matchOffset(comFeat, feat, tw, TPL_INSET);
                        int    dx   = (int) m[0];
                        double sc   = m[1];
                        double mg   = sc - m[2];   // peak − zero: 겹침 위치가 정지 위치보다 더 잘 맞는 정도
                        lastDx = dx; lastScore = sc;

                        // 보수 게이트: 새 내용(dx)·겹침이 둘 다 충분 + peak 높고 유일할 때만 trim
                        boolean trustOverlap =
                                dx >= MIN_SHIFT && dx <= roiW - tw
                             && sc >= OPAQUE_TRIM_SCORE && mg >= MARGIN;
                        // 2-밴드 합의: 다른 위치 밴드도 같은 dx여야 진짜 겹침(마디 주기 오매칭 차단)
                        if (trustOverlap && dx <= reach2) {
                            double[] m2 = ops.matchOffset(comFeat, feat, tw, secondInset);
                            if (m2[1] >= OPAQUE_TRIM_SCORE && Math.abs(dx - (int) m2[0]) > DX_AGREE_TOL)
                                trustOverlap = false;
                        }

                        // 신뢰 시 겹친 (roiW-dx)만 버리고 새로 드러난 dx만, 아니면 통째
                        Mat piece = trustOverlap
                            ? new Mat(roiColor, new Rect(roiW - dx, 0, dx, roiH)).clone()
                            : roiColor.clone();
                        colorStrips.add(piece);
                        comFeat.release(); comFeat = feat.clone();
                        canvasW += piece.cols();
                        pageCnt++;
                        if (trustOverlap) trimCnt++;
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
                    double[] m    = ops.matchOffset(comFeat, feat, tw, TPL_INSET);
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
                        double[] m2 = ops.matchOffset(comFeat, feat, tw, secondInset);
                        boolean confidentDisagree = m2[1] >= MIN_SCORE
                                                 && Math.abs(dx - (int) m2[0]) > DX_AGREE_TOL;
                        if (confidentDisagree) { isScroll = false; rejectCnt++; }
                    }

                    if (isScroll) {
                        colorStrips.add(new Mat(roiColor, new Rect(roiW - dx, 0, dx, roiH)).clone());
                        comFeat.release();  comFeat  = feat.clone();
                        comColor.release(); comColor = roiColor.clone();
                        canvasW += dx;
                        scrollCnt++;
                        scrolled = true;
                    } else if (!scrolled && zero >= SEED_REFRESH_SCORE) {
                        // 첫 스크롤 전 동일 화면(인트로) → 또렷한 최신 프레임으로 시드 교체.
                        colorStrips.get(0).release();
                        colorStrips.set(0, roiColor.clone());
                        comFeat.release();  comFeat  = feat.clone();
                        comColor.release(); comColor = roiColor.clone();
                        staticCnt++;
                    } else if (score < NEWPAGE_MAX_SCORE) {
                        // 확정 화면과 겹침을 못 찾음 → 완전히 새 화면일 수 있음(누락 방지).
                        double[] s = ops.matchOffset(lastFeat, feat, tw, TPL_INSET);
                        if (s[2] >= STABLE_SCORE) {     // 직전 샘플과 dx=0에서 일치 = 정지된 새 화면
                            // 반복 패턴이면 feature 매칭이 실패한 것일 수 있다. raw 회색조로 확정 화면과
                            // 겹침을 재확인 → 확실하면 그만큼만 잘라 중복 제거(누락 안전: 애매하면 통째).
                            double[] g = ops.matchOffsetGray(comColor, roiColor, tw, TPL_INSET);
                            int    gdx = (int) g[0];
                            double gsc = g[1];
                            if (gsc >= NEWPAGE_OVERLAP_SCORE && gdx >= 0 && gdx <= roiW - tw) {
                                if (gdx >= MIN_SHIFT) {            // 부분 겹침 → 새로 드러난 gdx만(스크롤로 재분류)
                                    colorStrips.add(new Mat(roiColor, new Rect(roiW - gdx, 0, gdx, roiH)).clone());
                                    comFeat.release();  comFeat  = feat.clone();
                                    comColor.release(); comColor = roiColor.clone();
                                    canvasW += gdx;
                                    scrollCnt++;
                                    scrolled = true;
                                } else {
                                    staticCnt++;                  // 전부 겹침 = 순수 중복 → 버림(이미 있으니 누락 아님)
                                }
                            } else {                              // 겹침 불확실 → 진짜 새 페이지로 보고 통째(기존 동작)
                                colorStrips.add(roiColor.clone());
                                comFeat.release();  comFeat  = feat.clone();
                                comColor.release(); comColor = roiColor.clone();
                                canvasW += roiW;
                                pageCnt++;
                                scrolled = true;
                            }
                        } else {
                            staticCnt++;   // 전환/블러 프레임 → 안정될 때까지 보류
                        }
                    } else {
                        staticCnt++;       // 정지(재생바만 이동) 또는 주기적 오매칭
                    }
                }

                lastFeat.release(); lastFeat = feat.clone();
                feat.release(); roiColor.release();

                if (sampleIdx > 0 && sampleIdx % (scanFps * 8) == 0) {
                    double pct     = lengthUs > 0 ? (double) currentUs / lengthUs * 100 : -1;
                    long   elapsed = (System.currentTimeMillis() - startMs) / 1000;
                    log(logger, "  진행 %.0f%% (%ds) 폭=%dpx | dx=%d score=%.2f | 스크롤%d 페이지%d 정지%d 합의거부%d 트림%d",
                        Math.max(pct, 0), elapsed, canvasW, lastDx, lastScore,
                        scrollCnt, pageCnt, staticCnt, rejectCnt, trimCnt);
                }

                sampleIdx++;
            }

            if (comFeat  != null) comFeat.release();
            if (comColor != null) comColor.release();
            if (lastFeat != null) lastFeat.release();

            if (colorStrips.isEmpty()) {
                log(logger, "[경고] 콘텐츠를 찾지 못했습니다.");
                return new ArrayList<>();
            }

            Mat panorama = new Mat();
            Core.hconcat(colorStrips, panorama);
            for (Mat s : colorStrips) s.release();
            log(logger, "[파노라마] 폭=%dpx 높이=%dpx | 스크롤%d 페이지%d 정지%d 합의거부%d 트림%d",
                panorama.cols(), panorama.rows(), scrollCnt, pageCnt, staticCnt, rejectCnt, trimCnt);

            List<Path> saved;
            if (mode == SheetMode.OPAQUE) {
                // 불투명: 이미 흰 종이+검정 악보라 이진화/노이즈 제거 없이 원본 그대로 잘라 저장(빠르고 충실).
                saved = sliceAndSave(panorama, roiW, outDir, logger);
                panorama.release();
            } else {
                Mat cleaned = ops.cleanForOutput(panorama);   // 반투명: 배경 제거 → 흰 종이+검은 표기
                panorama.release();
                saved = sliceAndSave(cleaned, roiW, outDir, logger);
                cleaned.release();
            }
            log(logger, "[완료] 총 %d줄 생성", saved.size());
            return saved;
        }
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
