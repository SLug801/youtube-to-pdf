package com.sheetmusic.app;

import com.sheetmusic.download.YtDlpDownloader;
import com.sheetmusic.pipeline.VideoProcessor;
import com.sheetmusic.vision.FrameExtractor;
import com.sheetmusic.vision.SheetMode;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JToggleButton;
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
    private JLabel timerLabel;
    private JCheckBox openFolderCheckbox;
    private javax.swing.Timer elapsedTimer;
    private long conversionStartMs;
    private FrameExtractor.RoiConfig currentRoi = FrameExtractor.RoiConfig.defaultConfig();
    private SheetMode currentMode = SheetMode.TRANSLUCENT;
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

        // 악보 배경 모드 선택(버튼)
        gbc.gridy = row++;
        panel.add(new JLabel("악보 배경 모드:"), gbc);

        JToggleButton modeTranslucent = new JToggleButton(SheetMode.TRANSLUCENT.label, true);
        JToggleButton modeOpaque      = new JToggleButton(SheetMode.OPAQUE.label);
        modeTranslucent.setToolTipText("반투명 패널 위 악보(뮤비/연주 배경이 옅게 비침). 연속 스크롤 영상.");
        modeOpaque.setToolTipText("흰 배경 + 검정 악보(스캔/PDF형, 페이지 넘김 영상 포함). 가장 깨끗하게 변환됨");

        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(modeTranslucent);
        modeGroup.add(modeOpaque);
        modeTranslucent.addActionListener(e -> currentMode = SheetMode.TRANSLUCENT);
        modeOpaque.addActionListener(e -> currentMode = SheetMode.OPAQUE);

        JPanel modeRow = new JPanel(new GridLayout(1, 2, 6, 0));
        modeRow.add(modeTranslucent);
        modeRow.add(modeOpaque);
        gbc.gridy = row++;
        panel.add(modeRow, gbc);

        // 완료되면 결과 폴더 열기
        openFolderCheckbox = new JCheckBox("완료되면 폴더 열기", true);
        openFolderCheckbox.setToolTipText("변환이 끝나면 PDF가 저장된 폴더를 자동으로 엽니다.");
        gbc.gridy = row++;
        panel.add(openFolderCheckbox, gbc);

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

    private JPanel createLogPanel() {
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        DefaultCaret caret = (DefaultCaret) logArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);

        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(new TitledBorder("변환 로그"));

        // 로그 박스 위쪽 — 변환 중 실시간 소요 시간만 표시
        timerLabel = new JLabel("소요 시간: 00:00");
        timerLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        timerLabel.setFont(timerLabel.getFont().deriveFont(java.awt.Font.BOLD, 13f));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(timerLabel, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(0, 184));
        return panel;
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

        // 확장자 떼고 → Windows 금지문자(\ / : * ? " < > |) 정리 → 다시 .pdf
        String base = filename.replaceAll("(?i)\\.pdf$", "");
        String sanitized = sanitizeFilename(base);
        if (sanitized.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "파일명에 사용할 수 있는 문자가 없습니다.", "입력 필요", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!sanitized.equals(base)) {
            appendLog("[안내] 파일명에 사용할 수 없는 문자가 있어 정리했습니다: \"" + sanitized + "\"");
        }
        String finalFilename = sanitized + ".pdf";
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
        startElapsedTimer();

        // 같은 URL이면 프리뷰에서 받은 영상 재사용
        final Path reuseVideo =
            (cachedVideo != null && url.equals(cachedUrl) && Files.exists(cachedVideo))
                ? cachedVideo : null;
        final SheetMode mode = currentMode;

        appendLog("악보 배경 모드: " + mode.label);

        currentWorker = new SwingWorker<String, String>() {
            @Override
            protected String doInBackground() throws Exception {
                return VideoProcessor.process(url, finalFilename.replaceAll("\\.pdf$", ""),
                    outputPdf, currentRoi, this::publish, this, reuseVideo, mode);
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
                        String result = get();
                        appendLog("변환 완료: " + result);
                        statusLabel.setText("완료");
                        if (result != null && openFolderCheckbox.isSelected())
                            openContainingFolder(result);
                    } else {
                        appendLog("변환이 취소되었습니다.");
                        statusLabel.setText("취소됨");
                    }
                } catch (Exception e) {
                    appendLog("변환 실패: " + e.getMessage());
                    statusLabel.setText("오류 발생");
                } finally {
                    String total = stopElapsedTimer();
                    appendLog("총 소요 시간: " + total);
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

    /** 변환 완료된 PDF가 들어 있는 폴더를 파일 탐색기로 연다(실패해도 변환 결과엔 영향 없음). */
    private void openContainingFolder(String pdfPath) {
        try {
            Path dir = Paths.get(pdfPath).toAbsolutePath().getParent();
            if (dir == null) return;
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir.toFile());
            } else {
                appendLog("[안내] 폴더 자동 열기를 지원하지 않는 환경입니다: " + dir);
            }
        } catch (Exception e) {
            appendLog("[안내] 폴더 열기 실패: " + e.getMessage());
        }
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

    /** 변환 시작 시각을 기록하고 1초마다 소요 시간 라벨을 갱신한다. */
    private void startElapsedTimer() {
        conversionStartMs = System.currentTimeMillis();
        timerLabel.setText("소요 시간: 00:00");
        if (elapsedTimer != null && elapsedTimer.isRunning()) elapsedTimer.stop();
        elapsedTimer = new javax.swing.Timer(1000, e ->
            timerLabel.setText("소요 시간: " + formatDuration(System.currentTimeMillis() - conversionStartMs)));
        elapsedTimer.start();
    }

    /** 타이머를 멈추고 최종 소요 시간 문자열을 반환(라벨도 고정값으로 갱신). */
    private String stopElapsedTimer() {
        if (elapsedTimer != null) elapsedTimer.stop();
        String total = formatDuration(System.currentTimeMillis() - conversionStartMs);
        timerLabel.setText("소요 시간: " + total);
        return total;
    }

    /** Windows에서 못 쓰는 파일명 문자(\ / : * ? " < > | 및 제어문자)를 _로 바꾸고 끝의 공백·점을 제거. */
    private static String sanitizeFilename(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_");
        cleaned = cleaned.replaceAll("[ .]+$", "").trim();   // 끝의 공백/점 제거(Windows 제약)
        return cleaned;
    }

    private static String formatDuration(long ms) {
        long totalSec = Math.max(0, ms) / 1000;
        long h = totalSec / 3600, m = (totalSec % 3600) / 60, s = totalSec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s)
                     : String.format("%02d:%02d", m, s);
    }

    private void onSelectionChanged(FrameExtractor.RoiConfig roi) {
        currentRoi = roi;
        roiLabel.setText("ROI: " + roi);
        BufferedImage crop = previewPanel.getCropImage();
        if (crop != null) cropPreviewPanel.setImage(crop);
    }
}
