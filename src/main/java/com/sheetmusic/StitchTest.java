package com.sheetmusic;

import java.nio.file.Path;
import java.util.List;

/** [테스트 전용] 다운로드된 로컬 영상에 스티칭+불투명을 적용해 PDF/조각을 생성, 패턴 점검. */
public class StitchTest {
    public static void main(String[] args) throws Exception {
        String video = (args.length > 0) ? args[0] : "optest2.mp4";
        double top    = (args.length > 1) ? Double.parseDouble(args[1]) : 0.80;
        double bottom = (args.length > 2) ? Double.parseDouble(args[2]) : 1.00;

        FrameExtractor.RoiConfig roi =
            new FrameExtractor.RoiConfig(top, bottom, 0.00, 1.00);
        FrameExtractor fx = new FrameExtractor(roi, SheetMode.OPAQUE);

        Path outDir = Path.of("stitchout");
        List<Path> frames = fx.extract(Path.of(video), outDir, ProgressLogger.console());
        System.out.println("[조각] " + frames.size() + "장");
        if (!frames.isEmpty()) {
            PdfBuilder.build(frames, Path.of("stitchout.pdf"));
            System.out.println("[PDF] stitchout.pdf");
        }
    }
}
