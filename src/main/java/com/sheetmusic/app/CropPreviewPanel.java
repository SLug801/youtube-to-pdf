package com.sheetmusic.app;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import javax.swing.JPanel;

/** 캡처(크롭)된 영역을 비율에 맞춰 보여주는 미리보기 패널. */
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
