package com.sheetmusic;

/**
 * 악보 영상의 배경 유형. 모드에 따라 매칭용 특징 추출(featureImage)과
 * 출력용 정리(cleanForOutput) 로직이 달라진다.
 */
public enum SheetMode {
    /** 반투명 패널 위 악보(뮤비/연주 배경이 옅게 비침). 연속 스크롤 누적 스티칭. */
    TRANSLUCENT("반투명"),
    /** 흰 배경 + 검정 악보(스캔/PDF형, 페이지 플립 영상 포함). 이진화 + 페이지 스냅샷 스티칭. */
    OPAQUE("불투명");

    public final String label;

    SheetMode(String label) { this.label = label; }

    @Override public String toString() { return label; }
}
