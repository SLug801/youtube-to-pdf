package com.sheetmusic;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

public class PdfBuilder {

    /**
     * 이미지 리스트를 A4 가로 PDF 한 파일로 합치기
     * (이미지를 페이지에 꽉 차게, 비율 유지)
     */
    public static void build(List<Path> imagePaths, Path outputPdf) throws IOException {
        if (imagePaths.isEmpty()) {
            System.err.println("[경고] 저장된 이미지가 없어 PDF를 생성하지 않습니다.");
            return;
        }

        System.out.printf("%n[PDF 생성] %d장 → %s%n", imagePaths.size(), outputPdf);

        try (PDDocument doc = new PDDocument()) {

            List<Path> sorted = imagePaths.stream().sorted().toList();

            for (int i = 0; i < sorted.size(); i++) {
                Path imgPath = sorted.get(i);

                PDImageXObject img = PDImageXObject.createFromFile(imgPath.toString(), doc);
                float imgW = img.getWidth();
                float imgH = img.getHeight();

                // 페이지 크기를 이미지 비율에 맞게 설정 (A4 가로 기준으로 스케일)
                float pageW = PDRectangle.A4.getHeight(); // A4 가로 = 841.89pt
                float pageH = PDRectangle.A4.getWidth();  // A4 세로 = 595.28pt

                // 비율 유지하며 페이지에 꽉 차게
                float scale = Math.min(pageW / imgW, pageH / imgH);
                float drawW = imgW * scale;
                float drawH = imgH * scale;
                float x = (pageW - drawW) / 2f;
                float y = (pageH - drawH) / 2f;

                PDPage page = new PDPage(new PDRectangle(pageW, pageH));
                doc.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(img, x, y, drawW, drawH);
                }

                if ((i + 1) % 10 == 0) {
                    System.out.printf("  처리 중... %d/%d%n", i + 1, sorted.size());
                }
            }

            doc.save(outputPdf.toString());
        }

        System.out.println("[완료] PDF 저장됨: " + outputPdf);
    }
}
