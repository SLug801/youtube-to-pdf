package com.sheetmusic;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_java;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgcodecs.Imgcodecs;

import java.nio.file.Path;

/**
 * [테스트 전용] 불투명 모드 다중 프레임 빠른 확인 도구.
 *
 * 영상 전체 변환 없이 여러 지점에서 프레임을 뽑아 ROI 하단 띠에 불투명 처리를 적용하고
 * 원본/출력/특징 이미지를 저장한다. FrameExtractor의 불투명 로직·임계값을 조정하며 반복 실행.
 *
 * 사용: java -cp ... com.sheetmusic.OpaqueFrameTest <video.mp4> [pos1 pos2 ...]
 *   pos: 0.0~1.0 영상 내 상대 위치(생략 시 0.2 0.4 0.6 0.8)
 * 결과: optest_pNN_raw.png(원본 띠) / _out.png(정리본) / _feat.png(매칭특징)
 */
public class OpaqueFrameTest {

    /** 처리할 세로 띠 [BAND_TOP, BAND_BOTTOM] (영상 높이 대비 비율). 이 영상은 악보가 상단. */
    private static final double BAND_TOP    = 0.08;
    private static final double BAND_BOTTOM = 0.50;

    public static void main(String[] args) throws Exception {
        Loader.load(opencv_java.class);

        String video = (args.length > 0) ? args[0] : "optest.mp4";
        double[] pos = (args.length > 1)
            ? parsePositions(args)
            : new double[]{0.2, 0.4, 0.6, 0.8};

        FrameExtractor fx = new FrameExtractor(
            FrameExtractor.RoiConfig.defaultConfig(), SheetMode.OPAQUE);

        for (double p : pos) {
            Mat full = FrameExtractor.captureFrameMat(Path.of(video), p);
            int w = full.cols(), h = full.rows();
            int y0 = (int) (h * BAND_TOP);
            int y1 = (int) (h * BAND_BOTTOM);
            Mat band = new Mat(full, new Rect(0, y0, w, y1 - y0));

            Mat out  = fx.debugOpaqueOutput(band);
            Mat feat = fx.debugOpaqueFeature(band);

            String tag = String.format("optest_p%02d", (int) Math.round(p * 100));
            Imgcodecs.imwrite(tag + "_raw.png",  band);
            Imgcodecs.imwrite(tag + "_out.png",  out);
            Imgcodecs.imwrite(tag + "_feat.png", feat);

            // 좌측 1/3 구간을 3배 확대해 프렛 숫자 가독성 비교
            int zw = band.cols() / 3;
            Mat rz = new Mat(), oz = new Mat();
            org.opencv.imgproc.Imgproc.resize(
                new Mat(band, new Rect(0, 0, zw, band.rows())), rz,
                new org.opencv.core.Size(zw * 3, band.rows() * 3), 0, 0,
                org.opencv.imgproc.Imgproc.INTER_NEAREST);
            org.opencv.imgproc.Imgproc.resize(
                new Mat(out, new Rect(0, 0, zw, out.rows())), oz,
                new org.opencv.core.Size(zw * 3, out.rows() * 3), 0, 0,
                org.opencv.imgproc.Imgproc.INTER_NEAREST);
            Imgcodecs.imwrite(tag + "_zoomraw.png", rz);
            Imgcodecs.imwrite(tag + "_zoomout.png", oz);
            rz.release(); oz.release();
            System.out.printf("[%s] %dx%d band -> _raw/_out/_feat 저장%n", tag, band.cols(), band.rows());

            full.release(); band.release(); out.release(); feat.release();
        }
        System.out.println("[완료]");
    }

    private static double[] parsePositions(String[] args) {
        double[] p = new double[args.length - 1];
        for (int i = 1; i < args.length; i++) p[i - 1] = Double.parseDouble(args[i]);
        return p;
    }
}
