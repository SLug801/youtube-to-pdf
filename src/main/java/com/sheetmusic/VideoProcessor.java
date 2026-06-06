package com.sheetmusic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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

    public static String process(
            String url,
            String title,
            Path outputPdf,
            double threshold,
            FrameExtractor.RoiConfig roi,
            ProgressLogger logger
    ) throws Exception {
        return process(url, title, outputPdf, threshold, roi, logger, null);
    }

    public static String process(
            String url,
            String title,
            Path outputPdf,
            double threshold,
            FrameExtractor.RoiConfig roi,
            ProgressLogger logger,
            javax.swing.SwingWorker<?, ?> worker
    ) throws Exception {
        if (logger == null) {
            logger = ProgressLogger.console();
        }

        Path parent = outputPdf.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path workDir = Files.createTempDirectory("ytpdf-work-");
        Path framesDir = workDir.resolve(Config.FRAMES_DIR);
        final ProgressLogger finalLogger = logger;
        final Path finalOutputPdf = outputPdf;
        
        try {
            // 취소 확인
            if (worker != null && worker.isCancelled()) {
                throw new InterruptedException("작업이 취소되었습니다.");
            }

            finalLogger.log("[작업] 임시 디렉터리: " + workDir);
            Path videoFile = YtDlpDownloader.download(url, workDir, finalLogger);
            
            // 취소 확인
            if (worker != null && worker.isCancelled()) {
                throw new InterruptedException("작업이 취소되었습니다.");
            }

            FrameExtractor extractor = new FrameExtractor(threshold, roi);
            List<Path> frames = extractor.extract(videoFile, framesDir, finalLogger);
            
            // 취소 확인
            if (worker != null && worker.isCancelled()) {
                throw new InterruptedException("작업이 취소되었습니다.");
            }

            finalLogger.log("[PDF 생성] " + frames.size() + "장의 이미지를 PDF로 변환 중...");
            PdfBuilder.build(frames, finalOutputPdf, finalLogger, (current, total) -> {
                finalLogger.log(String.format("[PDF] %.0f%% (%d/%d)", (double)current/total*100, current, total));
            });
            
            Files.deleteIfExists(videoFile);
            finalLogger.log("[정리] 영상 파일 삭제됨 → 결과물: " + finalOutputPdf);
            return finalOutputPdf.toString();
        } finally {
            deleteDirectory(workDir);
        }
    }

    private static void deleteDirectory(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                  .forEach(path -> {
                      try {
                          Files.deleteIfExists(path);
                      } catch (IOException ignored) {
                      }
                  });
        } catch (IOException ignored) {
        }
    }

    // threshold만 받는 오버로드 (기본 ROI 사용)
    public static String process(String url, String title, double threshold) throws Exception {
        return process(url, title, threshold, FrameExtractor.RoiConfig.defaultConfig());
    }
}
