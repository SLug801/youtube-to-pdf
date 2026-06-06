package com.sheetmusic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class YtDlpDownloader {

    /**
     * yt-dlp가 설치되어 있는지 확인. 없으면 안내 후 종료.
     */
    public static void checkYtDlp() {
        try {
            ProcessBuilder pb = new ProcessBuilder("yt-dlp", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String version = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            System.out.println("[확인] yt-dlp 버전: " + version);
        } catch (Exception e) {
            System.err.println("""
                    [오류] yt-dlp가 설치되어 있지 않습니다.
                    
                    설치 방법:
                      pip install yt-dlp
                    또는
                      winget install yt-dlp          (Windows)
                      brew install yt-dlp            (Mac)
                    """);
            System.exit(1);
        }
    }

    /**
     * URL에서 영상 다운로드. 저장된 .mp4 파일 경로 반환.
     */
    public static Path download(String url, Path outputDir) throws IOException, InterruptedException {
        return download(url, outputDir, null);
    }

    public static Path download(String url, Path outputDir, ProgressLogger logger) throws IOException, InterruptedException {
        // 기존 MP4 파일 확인 (중복 다운로드 방지)
        try (var stream = Files.list(outputDir)) {
            var existingVideo = stream
                    .filter(p -> p.toString().endsWith(".mp4"))
                    .findFirst();
            if (existingVideo.isPresent()) {
                log(logger, "[확인] 기존 영상 파일 사용: " + existingVideo.get().getFileName());
                return existingVideo.get();
            }
        } catch (IOException e) {
            // 디렉토리가 없을 수 있음
        }

        log(logger, "[다운로드] " + url);

        Path outputTemplate = outputDir.resolve("video.%(ext)s");
        Files.createDirectories(outputDir);

        ProcessBuilder pb = new ProcessBuilder(
                "yt-dlp",
                "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
                "--merge-output-format", "mp4",
                "-o", outputTemplate.toString(),
                "--no-playlist",
                url
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();

        try (var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log(logger, line);
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IOException("yt-dlp 다운로드 실패 (exit code: " + exitCode + ")");
        }

        // 실제 저장된 파일 찾기
        try (var stream = Files.list(outputDir)) {
            return stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("video") &&
                               (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".webm"));
                    })
                    .findFirst()
                    .orElseThrow(() -> new IOException("다운로드된 영상 파일을 찾지 못했습니다."));
        }
    }

    private static void log(ProgressLogger logger, String message) {
        if (logger != null) {
            logger.log(message);
        } else {
            System.out.println(message);
        }
    }
}
