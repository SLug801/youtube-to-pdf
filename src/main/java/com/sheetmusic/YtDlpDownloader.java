package com.sheetmusic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class YtDlpDownloader {

    /**
     * 실행에 사용할 yt-dlp 실행파일 경로를 찾는다.
     * 배포본(jpackage app-image)에서는 PATH에 yt-dlp가 없으므로, 앱과 함께 동봉한
     * yt-dlp.exe를 우선 찾는다. 탐색 순서:
     *   1) 시스템 속성 -Dytpdf.ytdlp=경로
     *   2) 실행 중인 jar/앱과 같은 폴더의 yt-dlp(.exe) (배포본: app\yt-dlp.exe)
     *   3) 현재 작업 폴더의 yt-dlp.exe (개발 중 리포 루트)
     *   4) PATH 의 yt-dlp (직접 설치한 경우)
     */
    private static volatile String ytDlpCmd;

    static String ytDlp() {
        if (ytDlpCmd != null) return ytDlpCmd;
        ytDlpCmd = resolveYtDlp();
        return ytDlpCmd;
    }

    private static String resolveYtDlp() {
        // 1) 명시적 지정
        String prop = System.getProperty("ytpdf.ytdlp");
        if (prop != null && Files.isRegularFile(Path.of(prop))) return prop;

        // 2) 실행 jar/앱과 같은 폴더 (그리고 한 단계 위)
        try {
            Path code = Path.of(YtDlpDownloader.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = Files.isRegularFile(code) ? code.getParent() : code;
            for (Path base : new Path[]{ dir, dir != null ? dir.getParent() : null }) {
                if (base == null) continue;
                for (String name : new String[]{ "yt-dlp.exe", "yt-dlp" }) {
                    Path cand = base.resolve(name);
                    if (Files.isRegularFile(cand)) return cand.toString();
                }
            }
        } catch (Exception ignored) { }

        // 3) 현재 작업 폴더
        for (String name : new String[]{ "yt-dlp.exe", "yt-dlp" }) {
            Path cand = Path.of(name).toAbsolutePath();
            if (Files.isRegularFile(cand)) return cand.toString();
        }

        // 4) PATH 폴백
        return "yt-dlp";
    }

    /**
     * yt-dlp가 설치되어 있는지 확인. 없으면 안내 후 종료.
     */
    public static void checkYtDlp() {
        try {
            ProcessBuilder pb = new ProcessBuilder(ytDlp(), "--version");
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

        // bestvideo: 단일 비디오 스트림 (ffmpeg merge 불필요, 1080p H.264 우선)
        // 22/18: 폴백 (오디오 포함 progressive, 720p/360p)
        ProcessBuilder pb = new ProcessBuilder(
                ytDlp(),
                "-f", "bestvideo[vcodec^=avc][height<=1080]/bestvideo[height<=1080]/22/18/best",
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
