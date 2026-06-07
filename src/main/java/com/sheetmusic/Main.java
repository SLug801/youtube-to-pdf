package com.sheetmusic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTube 악보 영상 → PDF 변환 도구
 *
 * 사용법:
 *   java -jar youtube-to-pdf.jar <URL> [URL2] ...
 *   java -jar youtube-to-pdf.jar --file urls.txt
 *   java -jar youtube-to-pdf.jar --threshold 0.95 --roi 0.65,1.00,0.00,1.00 <URL>
 */
public class Main {

    public static void main(String[] args) {
        // JNA가 시스템 설치 라이브러리보다 프로젝트 내장 라이브러리를 우선하도록 설정
        // (Invalid memory access 오류 방지 핵심 설정)
        System.setProperty("jna.nosys", "true");
        System.setProperty("jna.protected", "true");

        if (args.length == 0 || (args.length == 1 && "--gui".equals(args[0]))) {
            GuiApp.show();
            return;
        }

        List<String> urls = new ArrayList<>();
        double threshold = Config.SIMILARITY_THRESHOLD;
        FrameExtractor.RoiConfig roi = FrameExtractor.RoiConfig.defaultConfig();
        int totalMeasures = 0;
        String filePath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--file", "-f"      -> { 
                    if (i + 1 < args.length) filePath = args[++i]; 
                    else System.err.println("[경고] --file 옵션 뒤에 경로가 누락되었습니다.");
                }
                case "--threshold", "-t" -> { 
                    if (i + 1 < args.length) {
                        try { threshold = Double.parseDouble(args[++i]); }
                        catch (NumberFormatException e) { System.err.println("[경고] 유사도 임계값이 숫자가 아닙니다. 기본값을 사용합니다."); }
                    } else System.err.println("[경고] --threshold 옵션 뒤에 값이 누락되었습니다.");
                }
                case "--roi", "-r"       -> { 
                    if (i + 1 < args.length) {
                        try { roi = FrameExtractor.RoiConfig.parse(args[++i]); }
                        catch (Exception e) { System.err.println("[경고] ROI 형식이 올바르지 않습니다. 기본값을 사용합니다."); }
                    } else System.err.println("[경고] --roi 옵션 뒤에 설정값이 누락되었습니다.");
                }
                case "--measures", "-m"   -> {
                    if (i + 1 < args.length) {
                        try { totalMeasures = Integer.parseInt(args[++i]); }
                        catch (NumberFormatException e) { System.err.println("[경고] 마디 수는 숫자여야 합니다."); }
                    }
                }
                case "--help", "-h"      -> { printHelp(); return; }
                default                  -> urls.add(args[i]);
            }
        }

        if (filePath != null) {
            try {
                List<String> lines = Files.readAllLines(Path.of(filePath));
                for (String line : lines) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) urls.add(line);
                }
            } catch (IOException e) {
                System.err.println("[오류] URL 파일을 읽을 수 없습니다: " + filePath);
                System.exit(1);
            }
        }

        if (urls.isEmpty()) { printHelp(); return; }

        YtDlpDownloader.checkYtDlp();

        System.out.printf("%n총 %d개 영상 처리 시작%n%s%n", urls.size(), "=".repeat(50));

        int success = 0, fail = 0;
        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            System.out.printf("%n[%d/%d] %s%n", i + 1, urls.size(), url);
            try {
                String baseFileName = String.format("sheet_%02d", i + 1);
                String pdf = VideoProcessor.process(url, baseFileName, threshold, roi, totalMeasures);
                System.out.println("✅ 완료: " + pdf);
                success++;
            } catch (Exception e) {
                System.err.println("❌ 오류: " + e.getMessage());
                fail++;
            }
        }

        System.out.printf("%n%s%n모든 작업 완료! (성공: %d, 실패: %d)%n", "=".repeat(50), success, fail);
    }

    private static void printHelp() {
        System.out.println("""
                ╔══════════════════════════════════════════════════╗
                ║       YouTube 악보 영상 → PDF 변환 도구          ║
                ╚══════════════════════════════════════════════════╝

                사용법:
                  java -jar youtube-to-pdf.jar --gui
                  java -jar youtube-to-pdf.jar <URL> [URL...]
                  java -jar youtube-to-pdf.jar --file urls.txt
                  java -jar youtube-to-pdf.jar --threshold 0.95 --roi 0.65,1.00,0.00,1.00 <URL>

                옵션:
                  --file, -f <파일>           URL 목록 텍스트 파일
                  --threshold, -t <값>        유사도 임계값 0~1 (기본: 0.97)
                                              낮출수록 더 많이 캡처
                  --roi, -r <top,bot,l,r>     악보 영역 비율 (기본: 0.70,1.00,0.00,1.00)
                                              zzero gu 영상 = 하단 30% → 0.70,1.00,0.00,1.00
                                              악보가 전체화면이면 → 0.00,1.00,0.00,1.00
                  --help, -h                  이 도움말

                ROI 조정 예시:
                  하단 35%만: --roi 0.65,1.00,0.00,1.00
                  하단 40%만: --roi 0.60,1.00,0.00,1.00
                  하단 절반:  --roi 0.50,1.00,0.00,1.00

                팁: 처음엔 기본값으로 실행해보고, 캡처 결과 보고 ROI 조정하세요!
                """);
    }
}
