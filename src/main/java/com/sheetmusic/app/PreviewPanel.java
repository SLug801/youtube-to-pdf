package com.sheetmusic.app;

import com.sheetmusic.vision.FrameExtractor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

import javax.swing.JPanel;

/** 프리뷰 이미지 위에 드래그로 조절 가능한 ROI(악보 영역) 박스를 그리는 패널. */
class PreviewPanel extends JPanel {

    private BufferedImage image;
    private Rectangle roiBox;           // image 좌표계

    private double scale  = 1.0;
    private int    imageX = 0;
    private int    imageY = 0;

    private static final double PREVIEW_SCALE = 0.92;
    private static final int    HANDLE_SIZE   = 10;  // screen px
    private static final int    MIN_BOX       = 20;  // image px

    // Handle order: 0=NW 1=N 2=NE 3=W 4=E 5=SW 6=S 7=SE
    private static final Cursor[] CURSORS = {
        Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR),
        Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR),
        Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR),
        Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR),
        Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR),
        Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR),
        Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR),
        Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR),
    };

    private int       activeHandle  = -1;   // -1=none 0-7=handle 8=move
    private Point     dragStart;
    private Rectangle boxAtDragStart;

    private final Consumer<FrameExtractor.RoiConfig> selectionCallback;

    PreviewPanel(Consumer<FrameExtractor.RoiConfig> selectionCallback) {
        this.selectionCallback = selectionCallback;
        setBackground(Color.BLACK);

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (image == null || roiBox == null) return;
                activeHandle = hitTest(e.getPoint());
                if (activeHandle >= 0) {
                    dragStart     = e.getPoint();
                    boxAtDragStart = new Rectangle(roiBox);
                }
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (activeHandle < 0 || dragStart == null) return;
                int dx = (int)((e.getX() - dragStart.x) / scale);
                int dy = (int)((e.getY() - dragStart.y) / scale);
                applyDrag(dx, dy);
                fireSelection();
                repaint();
            }
            @Override public void mouseReleased(MouseEvent e) {
                activeHandle = -1; dragStart = null; boxAtDragStart = null;
            }
            @Override public void mouseMoved(MouseEvent e) {
                if (image == null || roiBox == null) { setCursor(Cursor.getDefaultCursor()); return; }
                int h = hitTest(e.getPoint());
                if      (h >= 0 && h < 8) setCursor(CURSORS[h]);
                else if (h == 8)           setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                else                       setCursor(Cursor.getDefaultCursor());
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
    }

    // ── public API ───────────────────────────────────────────────────────

    void setImage(BufferedImage img, FrameExtractor.RoiConfig roi) {
        this.image = img;
        if (img != null) initBox(roi, img.getWidth(), img.getHeight());
        repaint();
    }

    void clearImage() { image = null; roiBox = null; repaint(); }

    BufferedImage getCropImage() {
        if (image == null || roiBox == null) return null;
        int x = clamp(roiBox.x, 0, image.getWidth()  - 1);
        int y = clamp(roiBox.y, 0, image.getHeight() - 1);
        int w = clamp(roiBox.width,  1, image.getWidth()  - x);
        int h = clamp(roiBox.height, 1, image.getHeight() - y);
        return image.getSubimage(x, y, w, h);
    }

    // ── internal ─────────────────────────────────────────────────────────

    private void initBox(FrameExtractor.RoiConfig roi, int iw, int ih) {
        int x = (int)(roi.leftRatio()   * iw);
        int y = (int)(roi.topRatio()    * ih);
        int w = (int)((roi.rightRatio()  - roi.leftRatio())   * iw);
        int h = (int)((roi.bottomRatio() - roi.topRatio())    * ih);
        roiBox = new Rectangle(x, y, Math.max(MIN_BOX, w), Math.max(MIN_BOX, h));
    }

    // Returns screen coordinates of the 8 handles
    private int[] handleSX() {
        int bx = imageX + (int)(roiBox.x * scale);
        int bw = (int)(roiBox.width * scale);
        return new int[]{ bx, bx+bw/2, bx+bw, bx, bx+bw, bx, bx+bw/2, bx+bw };
    }
    private int[] handleSY() {
        int by = imageY + (int)(roiBox.y * scale);
        int bh = (int)(roiBox.height * scale);
        return new int[]{ by, by, by, by+bh/2, by+bh/2, by+bh, by+bh, by+bh };
    }

    private int hitTest(Point p) {
        if (roiBox == null) return -1;
        int hs = HANDLE_SIZE;
        int[] hx = handleSX(), hy = handleSY();
        for (int i = 0; i < 8; i++) {
            if (p.x >= hx[i]-hs/2 && p.x <= hx[i]+hs/2 &&
                p.y >= hy[i]-hs/2 && p.y <= hy[i]+hs/2) return i;
        }
        int bx = imageX + (int)(roiBox.x * scale);
        int by = imageY + (int)(roiBox.y * scale);
        int bw = (int)(roiBox.width * scale);
        int bh = (int)(roiBox.height * scale);
        if (p.x >= bx && p.x <= bx+bw && p.y >= by && p.y <= by+bh) return 8;
        return -1;
    }

    private void applyDrag(int dx, int dy) {
        int x = boxAtDragStart.x, y = boxAtDragStart.y;
        int r = x + boxAtDragStart.width, b = y + boxAtDragStart.height;
        switch (activeHandle) {
            case 0 -> { x += dx; y += dy; }
            case 1 -> { y += dy; }
            case 2 -> { r += dx; y += dy; }
            case 3 -> { x += dx; }
            case 4 -> { r += dx; }
            case 5 -> { x += dx; b += dy; }
            case 6 -> { b += dy; }
            case 7 -> { r += dx; b += dy; }
            case 8 -> { x += dx; r += dx; y += dy; b += dy; }
        }
        // Enforce minimum size
        if (r - x < MIN_BOX) { if (activeHandle==0||activeHandle==3||activeHandle==5) x = r-MIN_BOX; else r = x+MIN_BOX; }
        if (b - y < MIN_BOX) { if (activeHandle==0||activeHandle==1||activeHandle==2) y = b-MIN_BOX; else b = y+MIN_BOX; }
        // Clamp to image bounds
        if (image != null) {
            int iw = image.getWidth(), ih = image.getHeight();
            x = clamp(x, 0, iw-MIN_BOX); y = clamp(y, 0, ih-MIN_BOX);
            r = clamp(r, x+MIN_BOX, iw);  b = clamp(b, y+MIN_BOX, ih);
        }
        roiBox = new Rectangle(x, y, r-x, b-y);
    }

    private void fireSelection() {
        if (roiBox == null || image == null) return;
        double iw = image.getWidth(), ih = image.getHeight();
        selectionCallback.accept(new FrameExtractor.RoiConfig(
            roiBox.y / ih,
            (roiBox.y + roiBox.height) / ih,
            roiBox.x / iw,
            (roiBox.x + roiBox.width)  / iw));
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (image == null) {
            g.setColor(Color.GRAY);
            g.drawString("프리뷰를 불러오면 여기에 이미지가 표시됩니다.", 20, 30);
            return;
        }

        double base = Math.min((double)getWidth()/image.getWidth(), (double)getHeight()/image.getHeight());
        scale  = base * PREVIEW_SCALE;
        int dw = (int)(image.getWidth()  * scale);
        int dh = (int)(image.getHeight() * scale);
        imageX = (getWidth()  - dw) / 2;
        imageY = (getHeight() - dh) / 2;

        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(image, imageX, imageY, dw, dh, null);

        if (roiBox == null) return;

        int bx = imageX + (int)(roiBox.x * scale);
        int by = imageY + (int)(roiBox.y * scale);
        int bw = (int)(roiBox.width  * scale);
        int bh = (int)(roiBox.height * scale);

        // Semi-transparent fill
        g2.setColor(new Color(0, 120, 215, 55));
        g2.fillRect(bx, by, bw, bh);

        // Box border
        g2.setColor(new Color(0, 150, 255));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRect(bx, by, bw, bh);

        // 8 handles
        int[] hx = handleSX(), hy = handleSY();
        int hs = HANDLE_SIZE;
        for (int i = 0; i < 8; i++) {
            g2.setColor(Color.WHITE);
            g2.fillRect(hx[i]-hs/2, hy[i]-hs/2, hs, hs);
            g2.setColor(new Color(0, 120, 215));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawRect(hx[i]-hs/2, hy[i]-hs/2, hs, hs);
        }
    }
}
