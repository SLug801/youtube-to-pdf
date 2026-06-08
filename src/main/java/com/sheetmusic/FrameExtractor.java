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
    private static final int    SCAN_FPS     = 10;      // 초당 검사 프레임
    private static final double TPL_RATIO    = 0.12;    // 매칭 템플릿 폭(ROI 폭 대비). 작을수록
                                                        //   작은 중복·큰 점프 스크롤까지 검출.
    private static final int    TPL_INSET    = 0;       // 템플릿을 현재 프레임 왼쪽에서 떼는 위치
    private static final double MIN_SCORE    = 0.35;    // 겹침 신뢰 임계(이상이면 dx 채택)
    private static final double MARGIN       = 0.12;    // peak−zero 마진(실제 스크롤 vs 정지/주기 오매칭)
    private static final double SEED_REFRESH_SCORE = 0.80; // 첫 스크롤 전 동일화면 판정(시드 갱신)
    private static final double NEWPAGE_MAX_SCORE   = 0.30; // 이보다 매칭이 낮아야 "새 페이지" 후보
    private static final double STABLE_SCORE = 0.75;    // 정지(동일 화면) 판정 임계
    private static final int    MIN_SHIFT    = 3;       // 이보다 작은 dx는 정지로 간주
    private static final double CONTENT_MIN  = 0.004;   // 인트로(빈 화면) 판별 임계

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

    public FrameExtractor(RoiConfig roi) {
        this.roi = roi;
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

            long stepUs = (long)(1_000_000.0 / SCAN_FPS);

            log(logger, "[시작] 해상도=%dx%d | FPS=%.1f | 길이=%.1fs | 모드=전폭매칭(%dfps)",
                width, height, fps, durationSec, SCAN_FPS);
            log(logger, "[설정] ROI=%s | 템플릿=%dpx | 임계 match=%.2f stable=%.2f",
                roi, tw, MIN_SCORE, STABLE_SCORE);

            List<Mat> colorStrips = new ArrayList<>();
            Mat   comFeat   = null;       // 파노라마 프런티어에 해당하는 "확정 화면"의 특징(roiW 폭)
            Mat   lastFeat  = null;       // 직전 샘플 프레임의 특징(정지/새 페이지 판정용)
            int   canvasW   = 0;          // 누적 파노라마 폭
            boolean started = false;
            boolean scrolled = false;     // 첫 스크롤 발생 여부(이전엔 시드 갱신 허용)
            int   scrollCnt = 0, pageCnt = 0, staticCnt = 0;   // 스크롤 / 새 페이지 / 정지

            Java2DFrameConverter converter = new Java2DFrameConverter();
            long currentUs = 0, sampleIdx = 0;
            long startMs   = System.currentTimeMillis();
            int  lastDx = 0; double lastScore = 0;

            while (lengthUs <= 0 || currentUs <= lengthUs) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException("취소됨");

                grabber.setTimestamp(currentUs);
                Frame videoFrame = grabber.grabImage();
                if (videoFrame == null) break;

                Mat frame = frameToMat(videoFrame, converter);
                if (frame.empty()) { currentUs += stepUs; sampleIdx++; continue; }

                Mat roiColor = new Mat(frame, roiRect).clone();
                frame.release();
                Mat feat = featureImage(roiColor);

                if (!started) {
                    double cr = (double) Core.countNonZero(feat) / feat.total();
                    if (cr < CONTENT_MIN) {                 // 인트로(빈 화면) 스킵
                        feat.release(); roiColor.release();
                        currentUs += stepUs; sampleIdx++;
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
                    currentUs += stepUs; sampleIdx++;
                    continue;
                }

                // ── 확정 화면(comFeat) 대비 현재 프레임의 우측 이동량 dx 측정 ──
                double[] m    = matchOffset(comFeat, feat, tw, TPL_INSET);
                int    dx     = (int) m[0];
                double score  = m[1];          // 최적 위치(dx) 상관
                double zero   = m[2];          // dx=0(제로 시프트) 상관
                double margin = score - zero;  // 스크롤 위치가 정지 위치보다 얼마나 더 잘 맞는가
                lastDx = dx; lastScore = score;

                // 실제 스크롤: dx 위치가 dx=0보다 뚜렷이(MARGIN) 더 잘 맞을 때만 채택.
                // → 빈/희박한 마디의 미세 스크롤도 잡고(누락 방지), 정지·주기적 오매칭(peak≈zero)은
                //   거른다(중복 방지). 상관·diff 절대값만으론 둘을 못 가르므로 마진이 핵심.
                boolean isScroll = dx >= MIN_SHIFT && dx <= roiW - tw
                                && score >= MIN_SCORE && margin >= MARGIN;

                if (isScroll) {
                    colorStrips.add(new Mat(roiColor, new Rect(roiW - dx, 0, dx, roiH)).clone());
                    comFeat.release(); comFeat = feat.clone();
                    canvasW += dx;
                    scrollCnt++;
                    scrolled = true;
                } else if (!scrolled && zero >= SEED_REFRESH_SCORE) {
                    // 첫 스크롤 전 동일 화면(인트로) → 또렷한 최신 프레임으로 시드 교체.
                    // fade-in 등으로 첫 프레임이 흐려 출력이 하얗게 나오는 문제를 방지.
                    colorStrips.get(0).release();
                    colorStrips.set(0, roiColor.clone());
                    comFeat.release(); comFeat = feat.clone();
                    staticCnt++;
                } else if (score < NEWPAGE_MAX_SCORE) {
                    // 확정 화면과 겹침을 못 찾음 → 완전히 새 화면일 수 있음(누락 방지).
                    // 직전 샘플과 동일한 "정지된 새 화면"으로 확인될 때만 통째로 새 페이지 추가.
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

                lastFeat.release(); lastFeat = feat.clone();
                feat.release(); roiColor.release();

                if (sampleIdx > 0 && sampleIdx % (SCAN_FPS * 8) == 0) {
                    double pct     = lengthUs > 0 ? (double) currentUs / lengthUs * 100 : -1;
                    long   elapsed = (System.currentTimeMillis() - startMs) / 1000;
                    log(logger, "  진행 %.0f%% (%ds) 폭=%dpx | dx=%d score=%.2f | 스크롤%d 페이지%d 정지%d",
                        Math.max(pct, 0), elapsed, canvasW, lastDx, lastScore,
                        scrollCnt, pageCnt, staticCnt);
                }

                currentUs += stepUs;
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
            log(logger, "[파노라마] 폭=%dpx 높이=%dpx | 스크롤%d 페이지%d 정지%d",
                panorama.cols(), panorama.rows(), scrollCnt, pageCnt, staticCnt);

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
        Mat inv = new Mat();
        Core.bitwise_not(bh, inv);                        // 255-bh → 흰 배경 + 검은 표기
        bh.release();

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
