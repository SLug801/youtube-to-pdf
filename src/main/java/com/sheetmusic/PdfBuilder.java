package com.sheetmusic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

public class PdfBuilder {

    /**
     * 이미지 리스트를 A4 PDF로 합치기 (페이지에 맞춰 동적 배치)
     */
    public static void build(List<Path> imagePaths, Path outputPdf) throws IOException {
        build(imagePaths, outputPdf, null, null);
    }

    public static void build(List<Path> imagePaths, Path outputPdf, ProgressLogger logger) throws IOException {
        build(imagePaths, outputPdf, logger, null);
    }

    public static void build(List<Path> imagePaths, Path outputPdf, ProgressLogger logger, PdfProgressCallback callback) throws IOException {
        if (imagePaths.isEmpty()) {
            log(logger, "[경고] 저장된 이미지가 없어 PDF를 생성하지 않습니다.");
            return;
        }

        log(logger, "%n[PDF 생성] %d장 → %s", imagePaths.size(), outputPdf);

        try (PDDocument doc = new PDDocument()) {

            List<Path> sorted = imagePaths.stream().sorted().toList();
            // portrait (세로) A4: width = A4.getWidth(), height = A4.getHeight()
            float pageW = PDRectangle.A4.getWidth();  // 595.28pt (A4 가로)
            float pageH = PDRectangle.A4.getHeight(); // 841.89pt (A4 세로)
            float margin = 20f;
            float availableHeight = pageH - 2 * margin;
            float availableWidth = pageW - 2 * margin;

            PDPage currentPage = new PDPage(new PDRectangle(pageW, pageH));
            doc.addPage(currentPage);
            PDPageContentStream cs = new PDPageContentStream(doc, currentPage);
            
            float currentY = pageH - margin;
            int imageCount = 0;

            for (int i = 0; i < sorted.size(); i++) {
                Path imgPath = sorted.get(i);
                PDImageXObject img = PDImageXObject.createFromFile(imgPath.toString(), doc);
                
                float imgW = img.getWidth();
                float imgH = img.getHeight();

                // 이미지 한 장이 한 줄 전체를 차지하도록 배율 계산
                float currentScale = availableWidth / imgW;
                float drawW = availableWidth;
                float drawH = imgH * currentScale;

                // 무조건 새로운 줄로 배치 (1이미지 = 1줄 = 4마디)
                // 만약 현재 페이지에 공간이 없으면 새 페이지

                // 세로 공간이 부족하면 새 페이지로
                if (currentY - drawH < margin) {
                    // 새 페이지 시작
                    cs.close();
                    currentPage = new PDPage(new PDRectangle(pageW, pageH));
                    doc.addPage(currentPage);
                    cs = new PDPageContentStream(doc, currentPage);
                    currentY = pageH - margin;
                }

                // 왼쪽 마진에 맞춰 그리고 다음 줄로 이동
                cs.drawImage(img, margin, currentY - drawH, drawW, drawH);
                currentY -= (drawH + 25); // 줄 사이 간격을 25pt로 넉넉히 배치

                imageCount++;
                if (callback != null && imageCount % 5 == 0) {
                    callback.onProgress(imageCount, sorted.size());
                }
                
                if ((i + 1) % 20 == 0) {
                    log(logger, "  처리 중... %d/%d", i + 1, sorted.size());
                }
            }

            cs.close();
            doc.save(outputPdf.toString());
        }

        log(logger, "[완료] PDF 저장됨: " + outputPdf);
    }

    @FunctionalInterface
    public interface PdfProgressCallback {
        void onProgress(int current, int total);
    }

    private static void log(ProgressLogger logger, String format, Object... args) {
        String message = String.format(format, args);
        if (logger != null) {
            logger.log(message);
        } else {
            System.out.println(message);
        }
    }
}
