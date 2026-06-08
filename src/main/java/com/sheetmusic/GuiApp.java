package com.sheetmusic;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.TitledBorder;
import javax.swing.text.DefaultCaret;

public class GuiApp {

    private JFrame frame;
    private JTextField urlField;
    private JTextField filenameField;
    private JTextField folderField;
    private JButton previewButton;
    private JButton convertButton;
    private JButton cancelButton;
    private JButton browseButton;
    private JLabel roiLabel;
    private JLabel statusLabel;
    private PreviewPanel previewPanel;
    private CropPreviewPanel cropPreviewPanel;
    private JTextArea logArea;
    private FrameExtractor.RoiConfig currentRoi = FrameExtractor.RoiConfig.defaultConfig();
    private SwingWorker<?, ?> currentWorker = null;

    // 프리뷰에서 받은 영상 캐시 — 같은 URL 변환 시 재다운로드 방지
    private Path   cachedVideo  = null;
    private Path   cachedFolder = null;
    private String cachedUrl    = null;

    public static void show() {
        SwingUtilities.invokeLater(() -> new GuiApp().createAndShowGui());
    }

    private void createAndShowGui() {
        frame = new JFrame("YouTube TAB 악보 → PDF 변환기");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(10, 10));

        frame.add(createControlPanel(), BorderLayout.WEST);
        frame.add(createMainPanel(),    BorderLayout.CENTER);
        frame.add(createLogPanel(),     BorderLayout.SOUTH);

        frame.setSize(1200, 820);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        // 종료 시 캐시 임시 폴더 정리
        Runtime.getRuntime().addShutdownHook(new Thread(this::clearCache));
    }

    /** 보관 중인 프리뷰 영상 캐시를 삭제하고 상태를 초기화한다. */
    private void clearCache() {
        if (cachedFolder != null) {
            try (Stream<Path> stream = Files.walk(cachedFolder)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                      .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            } catch (IOException ignored) {}
        }
        cachedVideo = null; cachedFolder = null; cachedUrl = null;
    }

    // ── 컨트롤 패널 ───────────────────────────────────────────────────────────

    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new TitledBorder("작업 설정"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill   = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        int row = 0;

        gbc.gridy = row++;
        panel.add(new JLabel("YouTube URL:"), gbc);

        urlField = new JTextField();
        gbc.gridy = row++;
        panel.add(urlField, gbc);

        previewButton = new JButton("프리뷰 불러오기");
        previewButton.addActionListener(e -> loadPreview());
        convertButton = new JButton("변환 시작");
        convertButton.addActionListener(e -> startConversion());
        cancelButton = new JButton("취소");
        cancelButton.setEnabled(false);
        cancelButton.addActionListener(e -> cancelConversion());

        JPanel buttonRow = new JPanel(new GridLayout(1, 3, 8, 0));
        buttonRow.add(previewButton);
        buttonRow.add(convertButton);
        buttonRow.add(cancelButton);
        gbc.gridy = row++;
        panel.add(buttonRow, gbc);

        gbc.gridy = row++;
        panel.add(new JLabel("변환할 파일명:"), gbc);

        filenameField = new JTextField("sheetmusic");
        gbc.gridy = row++;
        panel.add(filenameField, gbc);

        gbc.gridy = row++;
        panel.add(new JLabel("저장 위치:"), gbc);

        JPanel folderRow = new JPanel(new BorderLayout(8, 0));
        folderField = new JTextField(System.getProperty("user.home"));
        browseButton = new JButton("폴더 선택");
        browseButton.addActionListener(e -> chooseFolder());
        folderRow.add(folderField, BorderLayout.CENTER);
        folderRow.add(browseButton, BorderLayout.EAST);
        gbc.gridy = row++;
        panel.add(folderRow, gbc);

        roiLabel = new JLabel("ROI: 하단 30% 전체 영역 (기본값)");
        gbc.gridy = row++;
        panel.add(roiLabel, gbc);

        JLabel hintLabel = new JLabel("<html>박스 모서리·가장자리를 드래그해<br>인식 영역을 조정하세요.</html>");
        hintLabel.setForeground(Color.DARK_GRAY);
        gbc.gridy = row++;
        panel.add(hintLabel, gbc);

        statusLabel = new JLabel("준비 완료");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        gbc.gridy = row++;
        panel.add(statusLabel, gbc);

        return panel;
    }

    // ── 메인 패널 (프리뷰 + 캡처 결과) ─────────────────────────────────────

    private JSplitPane createMainPanel() {
        previewPanel = new PreviewPanel(this::onSelectionChanged);
        previewPanel.setPreferredSize(new Dimension(760, 420));
        previewPanel.setBorder(new TitledBorder("미리보기 — 박스로 인식 영역 조정"));

        cropPreviewPanel = new CropPreviewPanel();
        cropPreviewPanel.setPreferredSize(new Dimension(760, 200));
        cropPreviewPanel.setBorder(new TitledBorder("캡처 영역 미리보기"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
            new JScrollPane(previewPanel),
            cropPreviewPanel);
        split.setResizeWeight(0.68);
        split.setDividerSize(6);
        return split;
    }

    private JScrollPane createLogPanel() {
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        DefaultCaret caret = (DefaultCaret) logArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(new TitledBorder("변환 로그"));
        scroll.setPreferredSize(new Dimension(0, 160));
        return scroll;
    }

    // ── 액션 ─────────────────────────────────────────────────────────────────

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser(folderField.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("저장 위치 선택");
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION)
            folderField.setText(chooser.getSelectedFile().getAbsolutePath());
    }

    private void loadPreview() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "먼저 유튜브 링크를 입력해주세요.", "입력 필요", JOptionPane.WARNING_MESSAGE);
            return;
        }

        setBusy(true);
        appendLog("프리뷰 로딩 중... 잠시만 기다려주세요.");
        previewPanel.clearImage();
        cropPreviewPanel.clearImage();
        currentRoi = FrameExtractor.RoiConfig.defaultConfig();
        roiLabel.setText("ROI: 하단 30% 전체 영역 (기본값)");

        // 새 프리뷰 시작 → 이전 캐시 정리
        clearCache();

        new SwingWorker<BufferedImage, String>() {
            private Path tempFolder;
            private Path tempVideo;

            @Override
            protected BufferedImage doInBackground() throws Exception {
                tempFolder = Files.createTempDirectory("ytpdf-preview-");
                tempVideo  = YtDlpDownloader.download(url, tempFolder, this::publish);
                publish("중간 프레임 추출 중...");
                return FrameExtractor.captureFrame(tempVideo, 0.5, currentRoi);
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                for (String line : chunks) appendLog(line);
            }

            @Override
            protected void done() {
                boolean ok = false;
                try {
                    BufferedImage img = get();
                    previewPanel.setImage(img, currentRoi);
                    // Show initial crop
                    BufferedImage crop = previewPanel.getCropImage();
                    if (crop != null) cropPreviewPanel.setImage(crop);
                    appendLog("프리뷰 완료. 박스를 조정해 캡처 영역을 설정하세요.");
                    statusLabel.setText("프리뷰 로드 완료");
                    ok = true;
                } catch (Exception e) {
                    appendLog("프리뷰 오류: " + e.getMessage());
                    statusLabel.setText("프리뷰 오류");
                } finally {
                    setBusy(false);
                    if (ok && tempVideo != null) {
                        // 다운로드 영상 보관 → 변환 시 재사용
                        cachedVideo  = tempVideo;
                        cachedFolder = tempFolder;
                        cachedUrl    = url;
                    } else {
                        // 실패 시 임시 파일 정리
                        try { if (tempVideo  != null) Files.deleteIfExists(tempVideo);  } catch (IOException ignored) {}
                        try { if (tempFolder != null) Files.deleteIfExists(tempFolder); } catch (IOException ignored) {}
                    }
                }
            }
        }.execute();
    }

    private void startConversion() {
        final String url = urlField.getText().trim();
        if (url.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "먼저 유튜브 링크를 입력해주세요.", "입력 필요", JOptionPane.WARNING_MESSAGE);
            return;
        }

        final String filename = filenameField.getText().trim();
        if (filename.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "파일명을 입력해주세요.", "입력 필요", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String finalFilename = filename.toLowerCase().endsWith(".pdf") ? filename : filename + ".pdf";
        Path targetFolder = Paths.get(folderField.getText().trim());
        try {
            Files.createDirectories(targetFolder);
        } catch (IOException e) {
            appendLog("저장 위치를 만들 수 없습니다: " + e.getMessage());
            return;
        }

        final Path outputPdf = targetFolder.resolve(finalFilename);

        setBusy(true);
        cancelButton.setEnabled(true);
        appendLog("변환 시작...");
        statusLabel.setText("변환 중...");

        // 같은 URL이면 프리뷰에서 받은 영상 재사용
        final Path reuseVideo =
            (cachedVideo != null && url.equals(cachedUrl) && Files.exists(cachedVideo))
                ? cachedVideo : null;

        currentWorker = new SwingWorker<String, String>() {
            @Override
            protected String doInBackground() throws Exception {
                return VideoProcessor.process(url, finalFilename.replaceAll("\\.pdf$", ""),
                    outputPdf, currentRoi, this::publish, this, reuseVideo);
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                for (String line : chunks) {
                    if (line.startsWith("FRAME_SAVED:")) updateCapturedPreview(line.substring(12));
                    else appendLog(line);
                }
            }

            @Override
            protected void done() {
                try {
                    if (!isCancelled()) {
                        appendLog("변환 완료: " + get());
                        statusLabel.setText("완료");
                    } else {
                        appendLog("변환이 취소되었습니다.");
                        statusLabel.setText("취소됨");
                    }
                } catch (Exception e) {
                    appendLog("변환 실패: " + e.getMessage());
                    statusLabel.setText("오류 발생");
                } finally {
                    setBusy(false);
                    cancelButton.setEnabled(false);
                    currentWorker = null;
                }
            }
        };
        currentWorker.execute();
    }

    private void updateCapturedPreview(String pathStr) {
        SwingUtilities.invokeLater(() -> {
            try {
                BufferedImage img = ImageIO.read(Paths.get(pathStr).toFile());
                cropPreviewPanel.setImage(img);
                statusLabel.setText("실시간 캡처 중: " + Paths.get(pathStr).getFileName());
            } catch (IOException ignored) {}
        });
    }

    private void cancelConversion() {
        if (currentWorker != null) {
            currentWorker.cancel(true);
            appendLog("[취소 중...] 변환을 취소합니다.");
        }
    }

    private void setBusy(boolean busy) {
        previewButton.setEnabled(!busy);
        convertButton.setEnabled(!busy);
        browseButton.setEnabled(!busy);
        urlField.setEnabled(!busy);
        filenameField.setEnabled(!busy);
        folderField.setEnabled(!busy);
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void onSelectionChanged(FrameExtractor.RoiConfig roi) {
        currentRoi = roi;
        roiLabel.setText("ROI: " + roi);
        BufferedImage crop = previewPanel.getCropImage();
        if (crop != null) cropPreviewPanel.setImage(crop);
    }

    // ── 캡처 결과 미리보기 패널 ────────────────────────────────────────────────

    private static class CropPreviewPanel extends JPanel {
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

    // ── 메인 프리뷰 패널 (조절 가능한 ROI 박스) ──────────────────────────────

    private static class PreviewPanel extends JPanel {

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
}
