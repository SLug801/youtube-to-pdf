package com.sheetmusic;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JSlider;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
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
    private JTextField measureField;
    private JSlider contrastSlider;
    private JTextField folderField;
    private JButton previewButton;
    private JButton convertButton;
    private JButton cancelButton;
    private JButton browseButton;
    private JLabel roiLabel;
    private JLabel statusLabel;
    private PreviewPanel previewPanel;
    private JTextArea logArea;
    private FrameExtractor.RoiConfig currentRoi = FrameExtractor.RoiConfig.defaultConfig();
    private SwingWorker<?, ?> currentWorker = null;

    public static void show() {
        SwingUtilities.invokeLater(() -> new GuiApp().createAndShowGui());
    }

    private void createAndShowGui() {
        frame = new JFrame("YouTube TAB 악보 → PDF 변환기");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout(10, 10));

        frame.add(createControlPanel(), BorderLayout.WEST);
        frame.add(createPreviewPanel(), BorderLayout.CENTER);
        frame.add(createLogPanel(), BorderLayout.SOUTH);

        frame.setSize(1100, 760);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new TitledBorder("작업 설정"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
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
        panel.add(new JLabel("전체 마디 수 (정확한 종료를 위해 입력):"), gbc);

        measureField = new JTextField("64");
        gbc.gridy = row++;
        panel.add(measureField, gbc);

        gbc.gridy = row++;
        panel.add(new JLabel("이미지 대비 (투명 타브 대응):"), gbc);
        contrastSlider = new JSlider(50, 300, 100); // 0.5 ~ 3.0배 (기본 1.0)
        contrastSlider.setMajorTickSpacing(50);
        contrastSlider.setPaintTicks(true);
        gbc.gridy = row++;
        panel.add(contrastSlider, gbc);

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

        JLabel hintLabel = new JLabel("미리보기에서 드래그로 인식할 영역을 선택하세요.");
        hintLabel.setForeground(Color.DARK_GRAY);
        gbc.gridy = row++;
        panel.add(hintLabel, gbc);

        statusLabel = new JLabel("준비 완료");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        gbc.gridy = row++;
        panel.add(statusLabel, gbc);

        return panel;
    }

    private JScrollPane createPreviewPanel() {
        previewPanel = new PreviewPanel(this::onSelectionChanged);
        previewPanel.setPreferredSize(new Dimension(760, 520));
        previewPanel.setBorder(new TitledBorder("미리보기 (드래그로 영역 선택)"));
        return new JScrollPane(previewPanel);
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
        scroll.setPreferredSize(new Dimension(0, 180));
        return scroll;
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser(folderField.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("저장 위치 선택");
        int selected = chooser.showOpenDialog(frame);
        if (selected == JFileChooser.APPROVE_OPTION) {
            folderField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
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
        currentRoi = FrameExtractor.RoiConfig.defaultConfig();
        roiLabel.setText("ROI: 하단 30% 전체 영역 (기본값)");

        new SwingWorker<BufferedImage, String>() {
            private Path tempFolder;
            private Path tempVideo;

            @Override
            protected BufferedImage doInBackground() throws Exception {
                tempFolder = Files.createTempDirectory("ytpdf-preview-");
                tempVideo = YtDlpDownloader.download(url, tempFolder, message -> publish(message));
                publish("중간 프레임 추출 중...");
                return FrameExtractor.captureFrame(tempVideo, 0.5, currentRoi);
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                for (String line : chunks) {
                    appendLog(line);
                }
            }

            @Override
            protected void done() {
                try {
                    BufferedImage image = get();
                    previewPanel.setImage(image);
                    appendLog("프리뷰 준비 완료했습니다. 관심 있는 영역을 드래그로 선택하세요.");
                    statusLabel.setText("프리뷰 로드 완료");
                } catch (Exception e) {
                    appendLog("프리뷰 오류: " + e.getMessage());
                    statusLabel.setText("프리뷰 오류");
                } finally {
                    setBusy(false);
                    if (tempVideo != null) {
                        try {
                            Files.deleteIfExists(tempVideo);
                        } catch (IOException ignored) {
                        }
                    }
                    if (tempFolder != null) {
                        try {
                            Files.deleteIfExists(tempFolder);
                        } catch (IOException ignored) {
                        }
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

        int totalMeasures = 0;
        try {
            totalMeasures = Integer.parseInt(measureField.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(frame, "마디 수는 숫자로 입력해주세요.", "입력 오류", JOptionPane.ERROR_MESSAGE);
            return;
        }

        final double contrast = contrastSlider.getValue() / 100.0;
        final String filename = filenameField.getText().trim();
        if (filename.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "파일명을 입력해주세요.", "입력 필요", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String finalFilename = filename;
        if (!finalFilename.toLowerCase().endsWith(".pdf")) {
            finalFilename += ".pdf";
        }
        final String pdfFilename = finalFilename;

        Path targetFolder = Paths.get(folderField.getText().trim());
        try {
            Files.createDirectories(targetFolder);
        } catch (IOException e) {
            appendLog("저장 위치를 만들 수 없습니다: " + e.getMessage());
            return;
        }

        final Path outputPdf = targetFolder.resolve(pdfFilename);

        setBusy(true);
        cancelButton.setEnabled(true);
        appendLog("변환 시작... 최종 PDF를 생성합니다.");
        statusLabel.setText("변환 중...");

        final int finalMeasures = totalMeasures;
        currentWorker = new SwingWorker<String, String>() {
            @Override
            protected String doInBackground() throws Exception {
                return VideoProcessor.process(url, pdfFilename.replaceAll("\\.pdf$", ""), outputPdf, Config.SIMILARITY_THRESHOLD, currentRoi, message -> publish(message), this, finalMeasures, contrast);
            }

            @Override
            protected void process(java.util.List<String> chunks) {
                for (String line : chunks) {
                    if (line.startsWith("FRAME_SAVED:")) {
                        String pathStr = line.substring(12);
                        updateCapturedPreview(pathStr);
                    } else {
                        appendLog(line);
                    }
                }
            }

            @Override
            protected void done() {
                try {
                    if (!isCancelled()) {
                        String pdf = get();
                        appendLog("변환 완료: " + pdf);
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
                previewPanel.setImage(img);
                statusLabel.setText("실시간 캡처 중: " + Paths.get(pathStr).getFileName());
            } catch (IOException ignored) {
            }
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
        appendLog("선택 영역 적용: " + roi);
    }

    private static class PreviewPanel extends JPanel {
        private BufferedImage image;
        private Rectangle selection;
        private double scale = 1.0;
        // 미리보기에서만 적용할 스케일 보정 (원본에 딱 맞지 않도록 약간 축소)
        private static final double PREVIEW_SCALE_FACTOR = 0.9;
        private int imageX;
        private int imageY;
        private final Consumer<FrameExtractor.RoiConfig> selectionCallback;
        private Point startPoint;

        public PreviewPanel(Consumer<FrameExtractor.RoiConfig> selectionCallback) {
            this.selectionCallback = selectionCallback;
            setBackground(Color.BLACK);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (image == null) {
                        return;
                    }
                    Point p = toImagePoint(e.getPoint());
                    if (p == null) {
                        return;
                    }
                    startPoint = p;
                    selection = new Rectangle(p);
                    repaint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (startPoint == null || image == null) {
                        return;
                    }
                    Point p = toImagePoint(e.getPoint());
                    if (p == null) {
                        return;
                    }
                    selection = createSelection(startPoint, p);
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (startPoint == null || image == null) {
                        return;
                    }
                    Point p = toImagePoint(e.getPoint());
                    if (p != null) {
                        selection = createSelection(startPoint, p);
                        repaint();
                        applySelection();
                    }
                    startPoint = null;
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        public void setImage(BufferedImage image) {
            this.image = image;
            this.selection = null;
            repaint();
        }

        public void clearImage() {
            this.image = null;
            this.selection = null;
            repaint();
        }

        private void applySelection() {
            if (selection == null || selection.width < 10 || selection.height < 10) {
                return;
            }
            double left = selection.x / (double) image.getWidth();
            double top = selection.y / (double) image.getHeight();
            double right = (selection.x + selection.width) / (double) image.getWidth();
            double bottom = (selection.y + selection.height) / (double) image.getHeight();
            selectionCallback.accept(new FrameExtractor.RoiConfig(top, bottom, left, right));
        }

        private Rectangle createSelection(Point a, Point b) {
            int x = Math.min(a.x, b.x);
            int y = Math.min(a.y, b.y);
            int w = Math.max(1, Math.abs(a.x - b.x));
            int h = Math.max(1, Math.abs(a.y - b.y));
            return new Rectangle(x, y, w, h);
        }

        private Point toImagePoint(Point screenPoint) {
            if (image == null) {
                return null;
            }
            int x = screenPoint.x - imageX;
            int y = screenPoint.y - imageY;
            if (x < 0 || y < 0) {
                return null;
            }
            int ix = (int) Math.round(x / scale);
            int iy = (int) Math.round(y / scale);
            if (ix < 0 || iy < 0 || ix > image.getWidth() || iy > image.getHeight()) {
                return null;
            }
            return new Point(ix, iy);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image == null) {
                g.setColor(Color.GRAY);
                g.drawString("프리뷰를 불러오면 여기에 이미지가 표시됩니다.", 20, 30);
                return;
            }
            int availableWidth = getWidth();
            int availableHeight = getHeight();
            double baseScale = Math.min((double) availableWidth / image.getWidth(), (double) availableHeight / image.getHeight());
            // 프리뷰에서는 원본에 딱 맞추지 않고 약간 작게 표시
            scale = baseScale * PREVIEW_SCALE_FACTOR;
            int drawWidth = (int) (image.getWidth() * scale);
            int drawHeight = (int) (image.getHeight() * scale);
            imageX = (availableWidth - drawWidth) / 2;
            imageY = (availableHeight - drawHeight) / 2;
            g.drawImage(image, imageX, imageY, drawWidth, drawHeight, null);
            if (selection != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(0, 120, 215, 80));
                g2.fillRect(imageX + (int) Math.round(selection.x * scale), imageY + (int) Math.round(selection.y * scale),
                        (int) Math.round(selection.width * scale), (int) Math.round(selection.height * scale));
                g2.setColor(new Color(0, 120, 215));
                g2.setStroke(new BasicStroke(2));
                g2.drawRect(imageX + (int) Math.round(selection.x * scale), imageY + (int) Math.round(selection.y * scale),
                        (int) Math.round(selection.width * scale), (int) Math.round(selection.height * scale));
                g2.dispose();
            }
        }
    }
}
