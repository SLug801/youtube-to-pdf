package com.sheetmusic;

/**
 * 악보 영상의 <b>배경 종류</b>(축 1). 전처리(특징 추출)와 출력 정리 방식을 결정한다.
 * 스티칭 전략과는 무관하다 — 그건 {@link Motion}이 담당한다.
 */
public enum Background {
    /** 배경 비침: 뮤비·연주 영상이 악보 뒤로 옅게 보임 → 출력 시 블랙햇으로 배경 제거. */
    TRANSLUCENT("반투명"),
    /** 배경 없음: 흰 종이 + 검정 악보(스캔/PDF형) → 배경 제거 불필요, 원본 그대로 사용. */
    OPAQUE("불투명");

    public final String label;

    Background(String label) { this.label = label; }

    @Override public String toString() { return label; }
}
