
package com.sheetmusic;
 
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
 
public class VideoProcessor {
 
    /**
     * 간단 버전 (CLI용)
     */
    public static String process(
            String url,
            String title,
            double threshold,
            FrameExtractor.RoiConfig roi,
            int totalMeasures,
            double contrast
    ) throws Exception {
        String safeTitle = sanitizeTitle(title);
        Path workDir   = Path.of(safeTitle);
        Path framesDir = workDir.resolve(Config.FRAMES_DIR);
        Path pdfPath   = workDir.resolve(safeTitle + ".pdf");
 
        Files.createDirectories(workDir);
 
        Path videoFile = YtDlpDownloader.download(url, workDir);
        FrameExtractor extractor = new FrameExtractor(threshold, roi, totalMeasures, contrast);
        List<Path> frames = extractor.extract(videoFile, framesDir);
        PdfBuilder.build(frames, pdfPath);
 
        Files.deleteIfExists(videoFile);
        System.out.println("[정리] 영상 파일 삭제됨 → 결과물: " + workDir + "/");
        return pdfPath.toString();
    }
 

    /** threshold만 받는 오버로드 (기본 ROI 사용) */
    public static String process(String url, String title, double threshold) throws Exception {
        return process(url, title, threshold, FrameExtractor.RoiConfig.defaultConfig(), 0, 1.0);
    }


    /**
     * GUI용 (logger + SwingWorker 취소 지원)
     */
    public static String process(
            String url,
            String title,
            Path outputPdf,
            double threshold,
                       FrameExtractor.RoiConfig roi,
            ProgressLogger logger
    ) throws Exception {
        return process(url, title, outputPdf, threshold, roi, logger, null, 0, 1.0);
    }


 
    public static String process(
            String url,
            String title,
            Path outputPdf,
            double threshold,
            FrameExtractor.RoiConfig roi,
            ProgressLogger logger,
            javax.swing.SwingWorker<?, ?> worker,
            int totalMeasures,
            double contrast
    ) throws Exception {
        if (logger == null) logger = ProgressLogger.console();
 
        Path parent = outputPdf.getParent();
        if (parent != null) Files.createDirectories(parent);
 
        Path workDir   = Files.createTempDirectory("ytpdf-work-");
        Path framesDir = workDir.resolve(Config.FRAMES_DIR);
        final ProgressLogger log = logger;
 
        try {
            checkCancellation(worker);
            log.log("[작업] 임시 디렉터리: " + workDir);
 
            // 1. 다운로드
            Path videoFile = YtDlpDownloader.download(url, workDir, log);
            checkCancellation(worker);
 


            // 2. 프레임 추출
            FrameExtractor extractor = new FrameExtractor(threshold, roi, totalMeasures, contrast);
            List<Path> frames = extractor.extract(videoFile, framesDir, log);
            checkCancellation(worker);

 
            if (frames.isEmpty()) {
                log.log("[경고] 캡처된 프레임이 없습니다. ROI 설정이나 threshold를 확인하세요.");
                return null;
            }
 
            // 3. PDF 생성
            log.log("[PDF 생성] " + frames.size() + "장 변환 중...");
            PdfBuilder.build(frames, outputPdf, log, (current, total) ->
                log.log(String.format("[PDF] %.0f%% (%d/%d)", (double)current/total*100, current, total)));
 
            Files.deleteIfExists(videoFile);
            log.log("[완료] 결과물: " + outputPdf);
            return outputPdf.toString();
 
        } catch (InterruptedException | java.util.concurrent.CancellationException e) {
            log.log("[중단] 사용자가 작업을 취소했습니다.");
            return null;
        } finally {
            deleteDirectory(workDir);
        }
    }
 
    // ── 유틸 ─────────────────────────────────────────────────────────────────
 
    private static String sanitizeTitle(String title) {
        String safe = title.replaceAll("[^a-zA-Z0-9가-힣_\\- ]", "").trim();
        return safe.length() > 50 ? safe.substring(0, 50) : safe;
    }
 
    private static void checkCancellation(javax.swing.SwingWorker<?, ?> worker)
            throws InterruptedException {
        if (worker != null && worker.isCancelled()) {
            throw new InterruptedException("작업이 중단되었습니다.");
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("스레드가 인터럽트되었습니다.");
        }
    }
 
    private static void deleteDirectory(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                  .forEach(p -> {
                      try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                  });
        } catch (IOException ignored) {}
    }
}