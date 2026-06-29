package com.sheetmusic;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import javax.swing.JPanel;

/** 현재 ROI로 잘릴 영역(또는 실시간 캡처 결과)을 비율 유지로 보여 주는 패널. */
class CropPreviewPanel extends JPanel {
    private BufferedImage image;

    CropPreviewPanel() {
        setBackground(new Color(30, 30, 30));
    }

    void setImage(BufferedImage img) {
        this.image = img;
        repaint();
    }

    void clearImage() {
        this.image = null;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (image == null) {
            g.setColor(Color.GRAY);
            g.drawString("인식 박스를 조정하면 캡처될 영역이 여기에 표시됩니다.", 20, 30);
            return;
        }
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double scale = Math.min(
            (double) getWidth()  / image.getWidth(),
            (double) getHeight() / image.getHeight());
        int w = (int)(image.getWidth()  * scale);
        int h = (int)(image.getHeight() * scale);
        int x = (getWidth()  - w) / 2;
        int y = (getHeight() - h) / 2;
        g2.drawImage(image, x, y, w, h, null);
    }
}
