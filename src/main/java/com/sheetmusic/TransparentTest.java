package com.sheetmusic;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;

/**
 * [테스트 전용] 투명 모드 단일 프레임 빠른 확인 도구.
 *
 * 영상 전체 변환(수십 분) 없이, 저장해 둔 스크린샷 1장에 투명 처리를 적용해
 * 결과를 즉시 본다. FrameExtractor의 TRANSPARENT_* 상수를 바꿔가며 반복 실행.
 *
 * ── 사용법 ────────────────────────────────────────────────────────────────
 *  1) 투명 악보 영상에서 한 프레임을 캡처해 PNG로 저장.
 *     - 악보 띠만 잘라두면 더 정확(배경 영상이 적게 들어감). 전체 프레임도 가능.
 *  2) 실행:
 *     - IDE에서 이 클래스 Run, 또는
 *     - mvn -q exec:java -Dexec.mainClass=com.sheetmusic.TransparentTest -Dexec.args="test_frame.png"
 *       (exec 플러그인이 없으면 IDE 실행 권장)
 *  3) 결과 파일:
 *     - <입력>_out.png  : 흰 종이 + 검은 표기로 정리된 출력(이게 PDF에 들어갈 모습)
 *     - <입력>_feat.png : 매칭에 쓰는 특징 이미지(세로 획 위주)
 *  4) FrameExtractor의 TRANSPARENT_KERNEL / TRANSPARENT_DESAT / TRANSPARENT_SAT_MAX 를
 *     조정한 뒤 다시 실행 → _out.png 비교하며 수렴.
 *
 *  CROP_TOP_RATIO: 입력이 전체 프레임이면 하단 비율만 처리(기본 0.70 = 하단 30%).
 *                  이미 악보 띠만 잘라둔 이미지면 0.0 으로.
 */
public class TransparentTest {

    /** 입력이 전체 프레임일 때 처리할 상단 컷 비율(0.70 = 하단 30%만). 잘라둔 띠면 0.0. */
    private static final double CROP_TOP_RATIO = 0.0;

    public static void main(String[] args) throws Exception {
        Loader.load(opencv_java.class);

        String in = (args.length > 0) ? args[0] : "test_frame.png";
        Mat img = Imgcodecs.imread(in);
        if (img.empty()) {
            System.out.println("[오류] 이미지를 못 읽었습니다: " + in
                + "  (작업 폴더에 PNG를 두거나 경로를 인자로 주세요)");
            return;
        }

        int w = img.cols(), h = img.rows();
        int y0 = (int) (h * CROP_TOP_RATIO);
        Mat roiImg = new Mat(img, new Rect(0, y0, w, h - y0));

        FrameExtractor fx = new FrameExtractor(
            FrameExtractor.RoiConfig.defaultConfig(), SheetMode.TRANSPARENT);

        Mat outClean = fx.debugTransparentOutput(roiImg);
        Mat outFeat  = fx.debugTransparentFeature(roiImg);

        String base = in.replaceAll("\\.[^.]+$", "");
        Imgcodecs.imwrite(base + "_out.png",  outClean);
        Imgcodecs.imwrite(base + "_feat.png", outFeat);

        System.out.printf("[완료] 입력 %dx%d → ROI 하단 %.0f%% 처리%n",
            w, h, (1 - CROP_TOP_RATIO) * 100);
        System.out.println("  결과: " + base + "_out.png (정리본), " + base + "_feat.png (매칭특징)");

        img.release(); roiImg.release(); outClean.release(); outFeat.release();
    }
}
