package com.sheetmusic.vision;

import static com.sheetmusic.vision.ScanParams.*;

import com.sheetmusic.common.ProgressLogger;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import org.opencv.imgproc.Imgproc;

/**
 * 가로로 스크롤되는 TAB 악보 영상을 이어 붙이되, 한 화면 폭이 완성될 때마다
 * PDF용 이미지를 즉시 저장한다. 전체 파노라마를 메모리에 쌓지 않으므로 영상 길이가
 * 길어져도 스티칭 버퍼는 최대 두 화면 미만으로 유지된다.
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

    // 스캔/스티칭 튜닝 상수는 ScanParams로 분리(아래 static import). 값별 조정 근거는 그곳 주석 참조.

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

    private final RoiConfig    roi;
    private final Background    bg;      // 배경 종류 → 특징 추출·출력 정리 결정
    private final Motion        motion;  // 진행 방식 → 스티칭 전략 결정
    private final double        startSeconds;
    private final SheetImageOps imageOps;

    public FrameExtractor(RoiConfig roi) {
        this(roi, Background.TRANSLUCENT, Motion.SCROLL, 0);
    }

    public FrameExtractor(RoiConfig roi, Background bg, Motion motion) {
        this(roi, bg, motion, 0);
    }

    public FrameExtractor(RoiConfig roi, Background bg, Motion motion, double startSeconds) {
        if (!Double.isFinite(startSeconds) || startSeconds < 0)
            throw new IllegalArgumentException("추출 시작 시각은 0초 이상이어야 합니다.");
        this.roi    = roi;
        this.bg     = (bg     != null) ? bg     : Background.TRANSLUCENT;
        this.motion = (motion != null) ? motion : Motion.SCROLL;
        this.startSeconds = startSeconds;
        this.imageOps = new SheetImageOps(this.bg);
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
            long startUs = Math.round(startSeconds * 1_000_000.0);
            if (lengthUs > 0 && startUs >= lengthUs) {
                throw new IllegalArgumentException(String.format(
                        "추출 시작 시각(%.1f초)이 영상 길이(%.1f초)보다 깁니다.",
                        startSeconds, durationSec));
            }
            long scanLengthUs = lengthUs > 0 ? lengthUs - startUs : 0;
            int    width   = grabber.getImageWidth();
            int    height  = grabber.getImageHeight();
            Rect   roiRect = makeRoiRect(width, height);

            int roiW    = roiRect.width;
            int roiH    = roiRect.height;
            int tw      = clamp((int)(roiW * TPL_RATIO), 8, roiW - 1);

            // 순차 디코딩하며 N프레임마다 한 번 검사한다(매 샘플 seek 제거 → 속도↑, 결과 동일).
            // 화면 전환(페이지 넘김)은 한 화면이 오래 정지하므로 더 낮은 검사 FPS로 매칭 연산을 줄인다.
            final int scanFps = (motion == Motion.CUT) ? SCAN_FPS_CUT : SCAN_FPS;
            int frameSkip = Math.max(1, (int) Math.round((fps > 0 ? fps : scanFps) / (double) scanFps));

            log(logger, "[시작] 해상도=%dx%d | FPS=%.1f | 길이=%.1fs | 모드=전폭매칭(검사%dfps, %d프레임마다)",
                width, height, fps, durationSec, scanFps, frameSkip);
            log(logger, "[설정] 배경=%s | 진행=%s | ROI=%s | 템플릿=%dpx | 임계 match=%.2f stable=%.2f",
                bg.label, motion.label, roi, tw, MIN_SCORE, STABLE_SCORE);
            if (startUs > 0) {
                log(logger, "[구간] 영상 %.1f초부터 추출", startSeconds);
                grabber.setTimestamp(startUs);
            }

            StreamingRowWriter rowWriter = new StreamingRowWriter(roiW, roiH, outDir, logger);
            try {
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

            while (true) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException("취소됨");

                Frame videoFrame = grabber.grabImage();
                if (videoFrame == null) break;
                if (grabbedFrames++ % frameSkip != 0) continue;   // 검사 대상이 아닌 프레임은 디코딩만 하고 건너뜀
                currentUs = grabber.getTimestamp();

                Mat frame = frameToMat(videoFrame, converter);
                if (frame.empty()) { sampleIdx++; continue; }

                Mat roiColor = new Mat(frame, roiRect).clone();
                frame.release();
                Mat feat = imageOps.featureImage(roiColor);

                if (!started) {
                    double cr = (double) Core.countNonZero(feat) / feat.total();
                    boolean sheetFrame = motion != Motion.CUT || imageOps.hasSheetStructure(roiColor);
                    if (cr < CONTENT_MIN || !sheetFrame) {  // 인트로·비악보 화면 스킵
                        feat.release(); roiColor.release();
                        sampleIdx++;
                        continue;
                    }
                    // 첫 콘텐츠 프레임: 화면 전체를 시드로
                    rowWriter.seed(roiColor);
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

                if (motion == Motion.CUT) {
                    // ── 화면 전환: 페이지 스냅샷 ──
                    // 한 화면이 정지하다 하드컷으로 여러 마디씩 넘어가고 겹침은 얇은 슬리버뿐이라,
                    // 서브마디 정합(dx 측정)은 마디 주기성에 속아 누락/중복이 난다(실측 확인).
                    // 대신 "확정 페이지와 충분히 달라지고(전환) + 직전 프레임과 같아짐(안정)"이면
                    // 새 페이지를 통째로 한 행으로 붙인다 — 경계의 얇은 슬리버만 겹치고 누락은 없다.
                    // 화면 전환 판정은 왼쪽 템플릿 일부가 아니라 ROI 전체를 비교한다.
                    // 반복 리듬 페이지는 왼쪽 15%가 거의 같아도 뒤쪽 마디가 다르므로,
                    // 전체 폭을 사용해야 중간 페이지를 같은 화면으로 오인하지 않는다.
                    double simConf = sameScreenScore(comFeat, feat);
                    double simLast = sameScreenScore(lastFeat, feat);
                    lastDx = 0; lastScore = simConf;
                    boolean changed = simConf < CUT_SAME_SCREEN;    // 확정 페이지와 달라짐 = 전환됨
                    boolean stable  = simLast >= CUT_STABLE; // 직전과 동일 = 전환 끝나 안정
                    boolean sheetFrame = !changed || !stable || imageOps.hasSheetStructure(roiColor);

                    if (changed && stable && sheetFrame) {
                        // 새 페이지 확정. 기본은 통째 붙이기(누락 0). 단, 확정 페이지와의 겹침이
                        // "확실"할 때만 겹친 만큼 잘라 중복을 없앤다(애매하면 통째 = 중복 감수).
                        double[] m  = matchOffset(comFeat, feat, tw, TPL_INSET);
                        int    dx   = (int) m[0];
                        double sc   = m[1];
                        double mg   = sc - m[2];   // peak − zero: 겹침 위치가 정지 위치보다 더 잘 맞는 정도
                        lastDx = dx; lastScore = sc;

                        // 보수 게이트: 새 내용(dx)·겹침이 둘 다 충분 + peak 높고 유일할 때만 trim
                        boolean trustOverlap =
                                dx >= MIN_SHIFT && dx <= roiW - tw
                             && sc >= CUT_TRIM_SCORE && mg >= MARGIN;
                        // 2-밴드 합의: 다른 위치 밴드도 같은 dx여야 진짜 겹침(마디 주기 오매칭 차단)
                        if (trustOverlap && dx <= reach2) {
                            double[] m2 = matchOffset(comFeat, feat, tw, secondInset);
                            if (m2[1] >= CUT_TRIM_SCORE && Math.abs(dx - (int) m2[0]) > DX_AGREE_TOL)
                                trustOverlap = false;
                        }

                        // 신뢰 시 겹친 (roiW-dx)만 버리고 새로 드러난 dx만, 아니면 통째
                        int appendedW = trustOverlap ? dx : roiW;
                        if (trustOverlap) rowWriter.appendSlice(roiColor, roiW - dx, dx);
                        else              rowWriter.append(roiColor);
                        comFeat.release(); comFeat = feat.clone();
                        canvasW += appendedW;
                        pageCnt++;
                        if (trustOverlap) trimCnt++;
                        scrolled = true;
                        log(logger, "[화면 확정] t=%.1fs | confirmed=%.2f stable=%.2f%s",
                            currentUs / 1_000_000.0, simConf, simLast,
                            trustOverlap ? String.format(" | trim=%dpx", roiW - appendedW) : "");
                    } else if (!scrolled && !changed) {
                        // 첫 페이지 전 정지 구간 → 또렷한 최신 프레임으로 시드 교체(흐린 첫 프레임 방지)
                        rowWriter.replaceSeed(roiColor);
                        comFeat.release(); comFeat = feat.clone();
                        staticCnt++;
                    } else {
                        staticCnt++;   // 정지 또는 전환 중(미안정)
                    }
                } else {
                    // ── 스크롤: 연속 누적(확정화면 comFeat 대비 dx 측정) ──
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
                        if (confidentDisagree) {
                            isScroll = false; rejectCnt++;
                            log(logger, "[2밴드 거부] t=%.1fs dx1=%d dx2=%d (불일치>%dpx) → 스크롤 기각",
                                currentUs / 1_000_000.0, dx, (int) m2[0], DX_AGREE_TOL);
                        }
                    }

                    if (isScroll) {
                        rowWriter.appendSlice(roiColor, roiW - dx, dx);
                        comFeat.release();  comFeat  = feat.clone();
                        comColor.release(); comColor = roiColor.clone();
                        canvasW += dx;
                        scrollCnt++;
                        scrolled = true;
                    } else if (!scrolled && zero >= SEED_REFRESH_SCORE) {
                        // 첫 스크롤 전 동일 화면(인트로) → 또렷한 최신 프레임으로 시드 교체.
                        rowWriter.replaceSeed(roiColor);
                        comFeat.release();  comFeat  = feat.clone();
                        comColor.release(); comColor = roiColor.clone();
                        staticCnt++;
                    } else if (score < NEWPAGE_MAX_SCORE) {
                        // 확정 화면과 겹침을 못 찾음 → 완전히 새 화면일 수 있음(누락 방지).
                        double[] s = matchOffset(lastFeat, feat, tw, TPL_INSET);
                        if (s[2] >= STABLE_SCORE) {     // 직전 샘플과 dx=0에서 일치 = 정지된 새 화면
                            // 반복 패턴이면 feature 매칭이 실패한 것일 수 있다. raw 회색조로 확정 화면과
                            // 겹침을 재확인 → 확실하면 그만큼만 잘라 중복 제거(누락 안전: 애매하면 통째).
                            double[] g = matchOffsetGray(comColor, roiColor, tw, TPL_INSET);
                            int    gdx = (int) g[0];
                            double gsc = g[1];
                            if (gsc >= NEWPAGE_OVERLAP_SCORE && gdx >= 0 && gdx <= roiW - tw) {
                                if (gdx >= MIN_SHIFT) {            // 부분 겹침 → 새로 드러난 gdx만(스크롤로 재분류)
                                    rowWriter.appendSlice(roiColor, roiW - gdx, gdx);
                                    comFeat.release();  comFeat  = feat.clone();
                                    comColor.release(); comColor = roiColor.clone();
                                    canvasW += gdx;
                                    scrollCnt++;
                                    scrolled = true;
                                } else {
                                    staticCnt++;                  // 전부 겹침 = 순수 중복 → 버림(이미 있으니 누락 아님)
                                }
                            } else {                              // 겹침 불확실 → 진짜 새 페이지로 보고 통째(기존 동작)
                                rowWriter.append(roiColor);
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
                    double pct     = scanLengthUs > 0
                            ? (double) Math.max(0, currentUs - startUs) / scanLengthUs * 100 : -1;
                    long   elapsed = (System.currentTimeMillis() - startMs) / 1000;
                    log(logger, "  진행 %.0f%% (%ds) 폭=%dpx | dx=%d score=%.2f | %s",
                        Math.max(pct, 0), elapsed, canvasW, lastDx, lastScore,
                        counts(scrollCnt, pageCnt, staticCnt, rejectCnt, trimCnt));
                }

                sampleIdx++;
            }

            if (comFeat  != null) comFeat.release();
            if (comColor != null) comColor.release();
            if (lastFeat != null) lastFeat.release();

            if (!started) {
                log(logger, "[경고] 콘텐츠를 찾지 못했습니다.");
                return new ArrayList<>();
            }

            List<Path> saved = rowWriter.finish();
            log(logger, "[스트리밍 스티칭] 누적 폭=%dpx 높이=%dpx | 임시 버퍼≤%dpx | %s",
                canvasW, roiH, roiW * 2,
                counts(scrollCnt, pageCnt, staticCnt, rejectCnt, trimCnt));
            log(logger, "[완료] 총 %d줄 생성", saved.size());
            return saved;
            } finally {
                rowWriter.close();
            }
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
     * matchOffset의 raw 회색조 버전. featureImage(세로획)가 반복 패턴(트레몰로 등)에서 저변동으로
     * 무력화될 때, 오선·숫자 구조가 살아 있는 원본 회색조로 겹침을 더 안정적으로 잰다.
     * @return {dx, peakScore, zeroScore}
     */
    private double[] matchOffsetGray(Mat refColor, Mat curColor, int tw, int inset) {
        Mat rg = new Mat(), cg = new Mat();
        Imgproc.cvtColor(refColor, rg, Imgproc.COLOR_BGR2GRAY);
        Imgproc.cvtColor(curColor, cg, Imgproc.COLOR_BGR2GRAY);
        int h  = cg.rows();
        int cw = cg.cols();
        int tx = clamp(inset, 0, Math.max(0, cw - tw));
        Mat tpl = new Mat(cg, new Rect(tx, 0, tw, h));
        Mat res = new Mat();
        Imgproc.matchTemplate(rg, tpl, res, Imgproc.TM_CCOEFF_NORMED);
        Core.MinMaxLocResult mmr = Core.minMaxLoc(res);
        double zero = res.get(0, tx)[0];
        rg.release(); cg.release(); tpl.release(); res.release();
        return new double[]{ (int) mmr.maxLoc.x - tx, mmr.maxVal, zero };
    }

    /** 같은 크기의 두 화면 전체에 대한 정규화 상관. 화면 전환(CUT) 판정 전용. */
    private double sameScreenScore(Mat confirmed, Mat current) {
        if (confirmed.rows() != current.rows() || confirmed.cols() != current.cols()) return -1;
        Mat result = new Mat();
        Imgproc.matchTemplate(confirmed, current, result, Imgproc.TM_CCOEFF_NORMED);
        double score = result.get(0, 0)[0];
        result.release();
        return score;
    }

    /**
     * 이어 붙인 원본 조각을 최대 두 화면 미만의 버퍼에만 보관하고, 한 화면 폭이 완성될 때마다
     * 즉시 이미지 파일로 내보낸다. 첫 화면은 fade-in 시드 교체가 끝날 때까지 flush하지 않는다.
     */
    final class StreamingRowWriter implements AutoCloseable {
        private static final int MIN_LAST_ROW_WIDTH = 40;

        private final int chunkW;
        private final int rowH;
        private final Path outDir;
        private final ProgressLogger logger;
        private final List<Path> saved = new ArrayList<>();
        private Mat pending = new Mat();
        private int nextIndex;
        private boolean finished;

        StreamingRowWriter(int chunkW, int rowH, Path outDir, ProgressLogger logger) {
            this.chunkW = chunkW;
            this.rowH = rowH;
            this.outDir = outDir;
            this.logger = logger;
        }

        /** 첫 콘텐츠 화면. 실제 이동이 확인되기 전에는 더 또렷한 프레임으로 교체될 수 있다. */
        void seed(Mat frame) {
            pending.release();
            pending = frame.clone();
        }

        void replaceSeed(Mat frame) {
            if (!saved.isEmpty() || pending.cols() != chunkW) {
                throw new IllegalStateException("이미 확정된 악보 줄의 시드는 교체할 수 없습니다.");
            }
            seed(frame);
        }

        /** source의 픽셀을 복사해 버퍼에 추가하므로 호출자가 source를 계속 관리한다. */
        void append(Mat source) throws IOException {
            if (finished) throw new IllegalStateException("이미 완료된 스트리밍 출력입니다.");
            if (source == null || source.empty()) return;

            Mat combined = new Mat();
            if (pending.empty()) {
                source.copyTo(combined);
            } else {
                Core.hconcat(List.of(pending, source), combined);
            }
            pending.release();
            pending = combined;

            while (pending.cols() >= chunkW) {
                Mat row = new Mat(pending, new Rect(0, 0, chunkW, rowH)).clone();
                int remainingW = pending.cols() - chunkW;
                Mat remaining = remainingW > 0
                        ? new Mat(pending, new Rect(chunkW, 0, remainingW, rowH)).clone()
                        : new Mat();
                pending.release();
                pending = remaining;
                try {
                    saveRow(row);
                } finally {
                    row.release();
                }
            }
        }

        void appendSlice(Mat source, int x, int width) throws IOException {
            Mat slice = new Mat(source, new Rect(x, 0, width, rowH));
            try {
                append(slice);
            } finally {
                slice.release();
            }
        }

        /** 마지막 남은 조각을 흰 여백으로 패딩한 뒤 저장한다. */
        List<Path> finish() throws IOException {
            if (finished) return List.copyOf(saved);
            finished = true;

            if (!pending.empty() && pending.cols() >= MIN_LAST_ROW_WIDTH) {
                Mat padded = new Mat(rowH, chunkW, pending.type(), new Scalar(255, 255, 255));
                Mat target = new Mat(padded, new Rect(0, 0, pending.cols(), rowH));
                pending.copyTo(target);
                target.release();
                try {
                    saveRow(padded);
                } finally {
                    padded.release();
                }
            }
            pending.release();
            pending = new Mat();
            return List.copyOf(saved);
        }

        private void saveRow(Mat rawRow) throws IOException {
            Mat output = bg == Background.OPAQUE ? rawRow : imageOps.cleanForOutput(rawRow);
            Path path = outDir.resolve(String.format("frame_%04d.jpg", nextIndex++));
            boolean written;
            try {
                written = Imgcodecs.imwrite(path.toString(), output);
            } finally {
                if (output != rawRow) output.release();
            }
            if (!written) throw new IOException("악보 이미지 저장 실패: " + path);
            saved.add(path);
            log(logger, "FRAME_SAVED:%s", path.toAbsolutePath());
        }

        @Override
        public void close() {
            pending.release();
            pending = new Mat();
        }
    }

    // [테스트 전용] 불투명 모드 디버그 훅 — OpaqueFrameTest에서 호출. 앱 동작과 무관.
    public Mat debugOpaqueOutput(Mat colorFrame)  { return new SheetImageOps(Background.OPAQUE).cleanForOutput(colorFrame); }
    public Mat debugOpaqueFeature(Mat colorFrame) { return new SheetImageOps(Background.OPAQUE).featureImage(colorFrame); }

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

            long lengthUs = videoLengthUs(grabber);
            long posUs = (long)(lengthUs * positionRatio);
            return captureBufferedFrame(grabber, posUs);
        }
    }

    /** 영상 시작 기준 절대 시각의 프레임을 GUI ROI 프리뷰용 이미지로 반환한다. */
    public static BufferedImage captureFrameAtSeconds(
            Path videoPath, double seconds, RoiConfig roi) throws Exception {
        if (!Double.isFinite(seconds) || seconds < 0)
            throw new IllegalArgumentException("프리뷰 시각은 0초 이상이어야 합니다.");

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toString())) {
            grabber.start();
            long lengthUs = videoLengthUs(grabber);
            long posUs = Math.round(seconds * 1_000_000.0);
            if (posUs >= lengthUs) {
                throw new IllegalArgumentException(String.format(
                        "프리뷰 시각(%.1f초)이 영상 길이(%.1f초)보다 깁니다.",
                        seconds, lengthUs / 1_000_000.0));
            }
            return captureBufferedFrame(grabber, posUs);
        }
    }

    private static long videoLengthUs(FFmpegFrameGrabber grabber) {
        long lengthUs = grabber.getLengthInTime();
        if (lengthUs > 0) return lengthUs;

        int totalFrames = grabber.getLengthInFrames();
        double fps = grabber.getFrameRate();
        return (fps > 0 && totalFrames > 0)
                ? (long)(totalFrames / fps * 1_000_000L) : 30_000_000L;
    }

    private static BufferedImage captureBufferedFrame(FFmpegFrameGrabber grabber, long posUs)
            throws Exception {
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
        buf.release();
        frame.release();
        return img;
    }

    private static void log(ProgressLogger logger, String fmt, Object... args) {
        String msg = String.format(fmt, args);
        if (logger != null) logger.log(msg); else System.out.println(msg);
    }

    /** 카운터를 0이 아닌 것만 모아 표시(트림=불투명전용, 합의거부=반투명전용 등 모드별로 자동 생략). */
    private static String counts(int scroll, int page, int stat, int reject, int trim) {
        StringBuilder sb = new StringBuilder();
        if (scroll > 0) sb.append(" 스크롤").append(scroll);
        if (page   > 0) sb.append(" 페이지").append(page);
        if (stat   > 0) sb.append(" 정지").append(stat);
        if (reject > 0) sb.append(" 합의거부").append(reject);
        if (trim   > 0) sb.append(" 트림").append(trim);
        return sb.length() == 0 ? "-" : sb.toString().trim();
    }
}
