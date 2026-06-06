package com.sheetmusic;

import java.nio.file.*;
import java.util.List;

public class VideoProcessor {

    /**
     * URL 하나를 처리해서 PDF로 저장. PDF 경로 반환.
     */
    public static String process(
            String url,
            String title,
            double threshold,
            FrameExtractor.RoiConfig roi
    ) throws Exception {

        // 작업 폴더 생성
        String safeTitle = title.replaceAll("[^a-zA-Z0-9가-힣_\\- ]", "").trim();
        if (safeTitle.length() > 50) safeTitle = safeTitle.substring(0, 50);

        Path workDir  = Path.of(safeTitle);
        Path framesDir = workDir.resolve(Config.FRAMES_DIR);
        Path pdfPath  = workDir.resolve(safeTitle + ".pdf");
        Path videoTemplate = workDir.resolve("video.%(ext)s");

        Files.createDirectories(workDir);

        // 1. 다운로드
        Path videoFile = YtDlpDownloader.download(url, workDir);

        // 2. ROI 기반 프레임 추출
        FrameExtractor extractor = new FrameExtractor(threshold, roi);
        List<Path> frames = extractor.extract(videoFile, framesDir);

        // 3. PDF 생성
        PdfBuilder.build(frames, pdfPath);

        // 4. 영상 파일 삭제 (용량 절약, 원하지 않으면 이 줄 주석 처리)
        Files.deleteIfExists(videoFile);
        System.out.println("[정리] 영상 파일 삭제됨 → 결과물: " + workDir + "/");

        return pdfPath.toString();
    }

    // threshold만 받는 오버로드 (기본 ROI 사용)
    public static String process(String url, String title, double threshold) throws Exception {
        return process(url, title, threshold, FrameExtractor.RoiConfig.defaultConfig());
    }
}
