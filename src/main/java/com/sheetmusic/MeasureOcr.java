package com.sheetmusic;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import net.sourceforge.tess4j.Tesseract;

public class MeasureOcr {

    private static final Pattern NUM = Pattern.compile("\\b([1-9]\\d{0,3})\\b");

    private static final int DEBUG_SAVE_MAX = 5;
    private int debugSaveCount = 0;
    private String lastText = "";

    // JNA Tesseract — 크래시 누적 시 CLI로 전환
    private Tesseract tess;
    private int jnaCrashCount = 0;
    private static final int JNA_CRASH_LIMIT = 2;

    // CLI Tesseract 경로 (프로세스 격리 — JNA 상태 오염 우회)
    private static final String TESS_EXE  = findTesseractExe();
    private static final String TESS_DATA = findTessdataDir();

    public MeasureOcr() throws Exception {
        tess = buildTesseract();
    }

    private static Tesseract buildTesseract() throws Exception {
        Tesseract t = new Tesseract();
        initDatapath(t);
        t.setLanguage("eng");
        // tessedit_char_whitelist 제거: LSTM(OEM 1)과 호환 안 됨 → 크래시 원인
        // 숫자 필터는 parseHighest() 정규식으로 처리
        t.setOcrEngineMode(1);   // LSTM only
        t.setPageSegMode(7);     // PSM 7: 단일 텍스트 라인
        return t;
    }

    private static void initDatapath(Tesseract t) {
        String[] parents = {
            "C:/Program Files/Tesseract-OCR",
            "C:/Program Files (x86)/Tesseract-OCR",
            System.getProperty("user.dir"),
            System.getProperty("user.home"),
        };
        for (String parent : parents) {
            if (new File(parent, "tessdata").isDirectory()) {
                t.setDatapath(parent);
                return;
            }
        }
    }

    private static String findTesseractExe() {
        String[] candidates = {
            "C:/Program Files/Tesseract-OCR/tesseract.exe",
            "C:/Program Files (x86)/Tesseract-OCR/tesseract.exe",
        };
        for (String c : candidates) {
            if (new File(c).exists()) return c;
        }
        try {
            Process p = new ProcessBuilder("tesseract", "--version")
                .redirectErrorStream(true).start();
            p.waitFor(2, TimeUnit.SECONDS);
            if (p.exitValue() == 0) return "tesseract";
        } catch (Exception ignored) {}
        return null;
    }

    private static String findTessdataDir() {
        String[] parents = {
            "C:/Program Files/Tesseract-OCR",
            "C:/Program Files (x86)/Tesseract-OCR",
            System.getProperty("user.dir"),
            System.getProperty("user.home"),
        };
        for (String parent : parents) {
            if (new File(parent, "tessdata").isDirectory()) return parent + "/tessdata";
        }
        return null;
    }

    /**
     * searchRect 내에서 마디 번호를 인식해 가장 큰 값을 반환한다.
     * 인식 실패 시 -1. 절대 예외를 던지지 않는다.
     */
    public int detectHighestVisible(Mat frame, Rect searchRect) {
        try {
            // ── OpenCV 전처리 ───────────────────────────────────────────────
            Mat crop = new Mat(frame, searchRect);
            Mat gray = new Mat();
            Imgproc.cvtColor(crop, gray, Imgproc.COLOR_BGR2GRAY);
            crop.release();

            // 1단계: 원본 표준편차 체크 — 완전 단색 이미지(검정/흰 화면) 스킵
            org.opencv.core.MatOfDouble mean   = new org.opencv.core.MatOfDouble();
            org.opencv.core.MatOfDouble stddev = new org.opencv.core.MatOfDouble();
            Core.meanStdDev(gray, mean, stddev);
            double std     = stddev.toArray()[0];
            double meanVal = mean.toArray()[0];
            mean.release(); stddev.release();

            if (std < 8.0) {
                gray.release();
                lastText = "(uniform image, std=" + String.format("%.1f", std) + ")";
                return -1;
            }

            // 2단계: 어두운 배경 반전 + Otsu 이진화
            if (meanVal < 128) Core.bitwise_not(gray, gray);
            Imgproc.threshold(gray, gray, 0, 255, Imgproc.THRESH_OTSU);

            // 3단계: 이진화 후 내용 픽셀 비율 체크
            // — 빈 이미지(흑색 픽셀 < 0.5%)를 Tesseract에 넘기면 Invalid memory access 발생
            int nonZero = Core.countNonZero(gray);
            // gray는 흰배경(255)+검정내용(0) → 내용이 있다 = 0인 픽셀 = total - nonZero
            int contentPx = (int)(gray.total()) - nonZero;
            double contentRatio = (double) contentPx / gray.total();
            if (contentRatio < 0.005) {
                gray.release();
                lastText = "(blank after threshold, content=" + String.format("%.3f", contentRatio) + ")";
                return -1;
            }

            // 4단계: 숫자 획 굵게 (흰배경+검정글자 → erode = 검정 영역 확장)
            Mat dilKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT, new Size(2, 2));
            Imgproc.erode(gray, gray, dilKernel);
            dilKernel.release();

            // ── OpenCV → BufferedImage ──────────────────────────────────────
            MatOfByte buf = new MatOfByte();
            Imgcodecs.imencode(".png", gray, buf);
            byte[] pngBytes = buf.toArray();
            gray.release(); buf.release();
            if (pngBytes == null || pngBytes.length == 0) {
                lastText = "(empty image)";
                return -1;
            }

            // ── 안전한 크기로 스케일 ─────────────────────────────────────────
            // Tesseract LSTM은 너무 넓은 이미지(5000px+)에서 Invalid memory access 발생
            BufferedImage small = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (small == null) { lastText = "(ImageIO read failed)"; return -1; }

            final int TARGET_H = 120;
            final int MAX_W    = 2000;
            double scale = (double) TARGET_H / Math.max(1, small.getHeight());
            int sw = Math.min(MAX_W, Math.max(10, (int)(small.getWidth() * scale)));
            int sh = TARGET_H;

            BufferedImage scaled = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, sw, sh);
            g2.drawImage(small, 0, 0, sw, sh, null);
            g2.dispose();

            // 디버그: 처음 N장 저장
            if (debugSaveCount < DEBUG_SAVE_MAX) {
                try {
                    File dbg = new File(System.getProperty("user.dir"),
                        "ocr_debug_" + (debugSaveCount + 1) + ".png");
                    boolean saved = ImageIO.write(scaled, "png", dbg);
                    System.out.println("[OCR 디버그] " + (saved ? "저장" : "저장실패") + ": "
                        + dbg.getAbsolutePath()
                        + " (" + sw + "x" + sh + ")"
                        + " std=" + String.format("%.1f", std)
                        + " content=" + String.format("%.3f", contentRatio));
                } catch (Exception ignored) {}
                debugSaveCount++;
            }

            // ── OCR: JNA 우선, 크래시 누적 시 CLI로 전환 ─────────────────────
            if (jnaCrashCount < JNA_CRASH_LIMIT && tess != null) {
                return runJnaOcr(scaled);
            } else {
                return runCliOcr(scaled);
            }

        } catch (Throwable t) {
            lastText = "ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            return -1;
        }
    }

    private int runJnaOcr(BufferedImage img) {
        try {
            lastText = tess.doOCR(img).strip();
            return parseHighest(lastText);
        } catch (Throwable t) {
            lastText = "JNA ERROR: " + t.getMessage();
            jnaCrashCount++;
            System.out.println("[OCR] JNA 크래시 " + jnaCrashCount + "회"
                + (jnaCrashCount >= JNA_CRASH_LIMIT ? " → CLI 방식으로 전환" : ""));
            try { tess = buildTesseract(); } catch (Exception ignored) { tess = null; }
            return runCliOcr(img);
        }
    }

    private int runCliOcr(BufferedImage img) {
        if (TESS_EXE == null) {
            lastText = "(no CLI tesseract found)";
            return -1;
        }
        try {
            Path tmp = Files.createTempFile("ocr_", ".png");
            ImageIO.write(img, "png", tmp.toFile());

            List<String> cmd = new ArrayList<>();
            cmd.add(TESS_EXE);
            cmd.add(tmp.toString());
            cmd.add("stdout");
            cmd.add("-l"); cmd.add("eng");
            cmd.add("--psm"); cmd.add("7");
            cmd.add("--oem"); cmd.add("1");
            if (TESS_DATA != null) {
                cmd.add("--tessdata-dir");
                cmd.add(TESS_DATA);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            lastText = new String(p.getInputStream().readAllBytes()).strip();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            Files.deleteIfExists(tmp);

            return parseHighest(lastText);
        } catch (Exception e) {
            lastText = "CLI ERROR: " + e.getMessage();
            return -1;
        }
    }

    public String getLastText() { return lastText; }

    private static int parseHighest(String text) {
        if (text == null || text.isBlank()) return -1;
        Matcher m = NUM.matcher(text);
        int max = -1;
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n > max) max = n;
        }
        return max;
    }
}
