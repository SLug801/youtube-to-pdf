package com.sheetmusic;

public class Config {
    /** 이 값 이상이면 "같은 화면"으로 판단 (0~1) */
    public static final double SIMILARITY_THRESHOLD = 0.97;

    /** 같은 장면이어도 최소 이 초 이상 지나야 다시 비교 */
    public static final double MIN_SECONDS_BETWEEN = 1.0;

    /** 초당 몇 프레임 검사할지 (높을수록 정확하지만 느림) */
    public static final int FRAME_SAMPLE_RATE = 2;

    /** 캡처 이미지 저장 폴더명 */
    public static final String FRAMES_DIR = "captured_frames";
}
