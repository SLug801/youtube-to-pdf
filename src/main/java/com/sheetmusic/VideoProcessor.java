package com.sheetmusic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public class VideoProcessor {

    /** CLI용 */
    public static String process(
            String url,
            String title,
            FrameExtractor.RoiConfig roi
    ) throws Exception {
        String safeTitle = sanitizeTitle(title);
        Path workDir   = Path.of(safeTitle);
        Path framesDir = workDir.resolve(Config.FRAMES_DIR);
        Path pdfPath   = workDir.resolve(safeTitle + ".pdf");

        Files.createDirectories(workDir);

        Path videoFile = YtDlpDownloader.download(url, workDir);
        List<Path> frames = new FrameExtractor(roi).extract(videoFile, framesDir);
        PdfBuilder.build(frames, pdfPath);

        Files.deleteIfExists(videoFile);
        System.out.println("[정리] 영상 파일 삭제됨 → 결과물: " + workDir + "/");
        return pdfPath.toString();
    }

    /** GUI용 (logger + SwingWorker 취소 지원) */
    public static String process(
            String url,
            String title,
            Path outputPdf,
            FrameExtractor.RoiConfig roi,
            ProgressLogger logger,
            javax.swing.SwingWorker<?, ?> worker
    ) throws Exception {
        return process(url, title, outputPdf, roi, logger, worker, null);
    }

    /**
     * GUI용. {@code preDownloadedVideo}가 주어지고 존재하면 다운로드를 건너뛰고 재사용한다.
     */
    public static String process(
            String url,
            String title,
            Path outputPdf,
            FrameExtractor.RoiConfig roi,
            ProgressLogger logger,
            javax.swing.SwingWorker<?, ?> worker,
            Path preDownloadedVideo
    ) throws Exception {
        if (logger == null) logger = ProgressLogger.console();

        Path parent = outputPdf.getParent();
        if (parent != null) Files.createDirectories(parent);

        Path workDir   = Files.createTempDirectory("ytpdf-work-");
        Path framesDir = workDir.resolve(Config.FRAMES_DIR);
        final ProgressLogger log = logger;

        boolean reused = preDownloadedVideo != null && Files.exists(preDownloadedVideo);

        try {
            checkCancellation(worker);
            log.log("[작업] 임시 디렉터리: " + workDir);

            Path videoFile;
            if (reused) {
                log.log("[캐시] 기존 다운로드 영상 재사용: " + preDownloadedVideo.getFileName());
                videoFile = preDownloadedVideo;
            } else {
                log.log("[캐시] 재사용 불가 → 새 다운로드");
                videoFile = YtDlpDownloader.download(url, workDir, log);
            }
            checkCancellation(worker);

            List<Path> frames = new FrameExtractor(roi).extract(videoFile, framesDir, log);
            checkCancellation(worker);

            if (frames.isEmpty()) {
                log.log("[경고] 캡처된 프레임이 없습니다. ROI 설정을 확인하세요.");
                return null;
            }

            log.log("[PDF 생성] " + frames.size() + "장 변환 중...");
            PdfBuilder.build(frames, outputPdf, log, (current, total) ->
                log.log(String.format("[PDF] %.0f%% (%d/%d)", (double) current / total * 100, current, total)));

            // 재사용 파일은 호출자(GUI 캐시)가 관리하므로 삭제하지 않음
            if (!reused) Files.deleteIfExists(videoFile);
            log.log("[완료] 결과물: " + outputPdf);
            return outputPdf.toString();

        } catch (InterruptedException | java.util.concurrent.CancellationException e) {
            log.log("[중단] 사용자가 작업을 취소했습니다.");
            return null;
        } finally {
            deleteDirectory(workDir);   // 재사용 영상은 다른 폴더이므로 영향 없음
        }
    }

    private static String sanitizeTitle(String title) {
        String safe = title.replaceAll("[^a-zA-Z0-9가-힣_\\- ]", "").trim();
        return safe.length() > 50 ? safe.substring(0, 50) : safe;
    }

    private static void checkCancellation(javax.swing.SwingWorker<?, ?> worker)
            throws InterruptedException {
        if (worker != null && worker.isCancelled())
            throw new InterruptedException("작업이 중단되었습니다.");
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("스레드가 인터럽트되었습니다.");
    }

    private static void deleteDirectory(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder())
                  .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
