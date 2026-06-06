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
                
                // 이미지를 페이지 너비에 맞게 스케일
                float scale = availableWidth / imgW;
                float drawW = availableWidth;
                float drawH = imgH * scale;

                // 페이지에 맞는지 확인
                if (currentY - drawH < margin) {
                    // 새 페이지 시작
                    cs.close();
                    currentPage = new PDPage(new PDRectangle(pageW, pageH));
                    doc.addPage(currentPage);
                    cs = new PDPageContentStream(doc, currentPage);
                    currentY = pageH - margin;
                }

                // 이미지 그리기
                float x = margin;
                float y = currentY - drawH;
                cs.drawImage(img, x, y, drawW, drawH);
                currentY -= drawH + 5; // 5pt 간격

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
