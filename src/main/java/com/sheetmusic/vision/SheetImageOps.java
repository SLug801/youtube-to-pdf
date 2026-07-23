package com.sheetmusic.vision;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * 악보 프레임에 대한 순수 이미지 연산(매칭용 특징 추출 / 출력용 배경 제거 / 노이즈 제거).
 * 입력 Mat → 출력 Mat 의 순수 함수 집합으로, {@link Background}에 따라 반투명/불투명 처리를 분기한다.
 * 스티칭 상태머신({@link FrameExtractor})과 분리해 알고리즘 코드의 가독성을 높인다.
 */
class SheetImageOps {

    // ── 최종 출력 배경 노이즈 제거 ───────────────────────────────────────────
    private static final boolean DENOISE_OUTPUT  = true;  // 최종 결과물 노이즈 한 번 더 거르기 on/off
    private static final int     NOISE_FLOOR      = 45;   // (0~255) 대비 부스트 후 이 밝기 미만은
                                                          //   배경 텍스처로 보고 제거. 높일수록 더 빡세게.
    private static final int     NOISE_MIN_AREA   = 8;    // 이보다 작은 고립 덩어리(연결요소) 삭제.
                                                          //   면적 기준이라 긴 오선·기둥·숫자는 보존, 점 잡티만 제거.

    // ── 불투명(OPAQUE) 이진화 ─────────────────────────────────────────────────
    private static final int OPAQUE_BLOCK = 31;   // adaptiveThreshold 블록 크기(홀수). 클수록 큰 음영에 둔감.
    private static final int OPAQUE_C     = 12;   // 국소 배경 대비 잉크 판정 여유. 클수록 옅은 회색·잡티 덜 잡힘.

    private final Background bg;

    SheetImageOps(Background bg) {
        this.bg = (bg != null) ? bg : Background.TRANSLUCENT;
    }

    /**
     * 매칭/콘텐츠 판별용 특징 이미지.
     * 모폴로지 블랙햇으로 "(반투명) 패널 위 어두운 표기(숫자·마디선·기둥)"만 추출한다.
     * 블랙햇은 국소 대비 기반이라 전역 밝기/투명도에 무관 — 반투명이 강하거나(흐림),
     * 패널 없이 투명하거나, 너무 밝은 경우까지 표기를 안정적으로 살린다(adaptiveThreshold보다
     * 배경 잡음이 훨씬 적음, 실측 검증됨). 가로 오선은 제거해 세로 특징 위주로 남긴다.
     */
    Mat featureImage(Mat roiColor) {
        if (bg == Background.OPAQUE)      return featureImageOpaque(roiColor);
        // ── 이하 반투명(TRANSLUCENT) 기존 로직 ──
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

    /**
     * 출력용 배경 제거: 블랙햇으로 어두운 표기(오선 포함)만 추출해 "흰 종이 + 검은 표기"로 만든다.
     * 반투명 배경(뮤비/연주자)·과밝은 패널을 전역 밝기와 무관하게 제거한다(실측 검증됨).
     * 매칭용 featureImage와 달리 가로 오선은 보존한다(악보의 일부).
     */
    Mat cleanForOutput(Mat panoBGR) {
        if (bg == Background.OPAQUE)      return cleanForOutputOpaque(panoBGR);
        // ── 이하 반투명(TRANSLUCENT) 기존 로직 ──
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

    /**
     * 화면 전환 모드에서 프레임이 실제 악보 띠인지 확인한다.
     * 긴 수평선을 열기 연산으로 남긴 뒤 서로 떨어진 오선 행이 2개 이상인지 검사해,
     * 드럼 인트로·타이틀 카드·구독 화면을 악보 페이지로 오인하지 않게 한다.
     */
    boolean hasSheetStructure(Mat colorSrc) {
        return sheetLineGroups(colorSrc) >= 2;
    }

    private int sheetLineGroups(Mat colorSrc) {
        Mat gray = new Mat();
        Imgproc.cvtColor(colorSrc, gray, Imgproc.COLOR_BGR2GRAY);
        if (Core.mean(gray).val[0] < 110) Core.bitwise_not(gray, gray);

        Mat ink = new Mat();
        Imgproc.adaptiveThreshold(gray, ink, 255,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV,
                OPAQUE_BLOCK, OPAQUE_C);
        gray.release();

        int minLineLength = Math.max(30, colorSrc.cols() / 3);
        Mat horizontalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, new Size(minLineLength, 1));
        Mat horizontal = new Mat();
        Imgproc.morphologyEx(ink, horizontal, Imgproc.MORPH_OPEN, horizontalKernel);
        ink.release();
        horizontalKernel.release();

        int minPixelsPerRow = Math.max(20, colorSrc.cols() / 4);
        int lineGroups = 0;
        boolean insideLine = false;
        for (int y = 0; y < horizontal.rows(); y++) {
            Mat row = horizontal.row(y);
            boolean line = Core.countNonZero(row) >= minPixelsPerRow;
            row.release();
            if (line && !insideLine) lineGroups++;
            insideLine = line;
        }
        horizontal.release();
        return lineGroups;
    }

    // ── 불투명(OPAQUE) 모드 ──────────────────────────────────────────────────
    // 흰 배경 + 검정 악보(이미 깨끗한 스캔/PDF형). 단순 이진화로 또렷하게 만든다.
    // 반투명/투명과 달리 배경 억제용 모폴로지가 거의 필요 없다.

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
}
