package com.sheetmusic;

/**
 * 악보 영상의 배경 유형. 모드에 따라 매칭용 특징 추출(featureImage)과
 * 출력용 정리(cleanForOutput) 로직이 달라진다. 스티칭(병합) 로직 자체는 공통.
 */
public enum SheetMode {
    /** 반투명 패널 위 악보(뮤비/연주 배경이 옅게 비침). 기존 검증된 모드. */
    TRANSLUCENT("반투명"),
    /** 패널 없이 배경 영상 위에 악보가 직접 그려진 형태. 배경 억제가 더 어려움. */
    TRANSPARENT("투명"),
    /** 흰 배경 + 검정 악보(이미 깨끗한 스캔/PDF형). 단순 이진화로 충분. */
    OPAQUE("불투명");

    public final String label;

    SheetMode(String label) { this.label = label; }

    @Override public String toString() { return label; }
}
