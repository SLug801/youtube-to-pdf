package com.sheetmusic;

/**
 * 악보 영상의 <b>진행 방식</b>(축 2). 프레임을 이어 붙이는 스티칭 전략을 결정한다.
 * 배경 종류와는 무관하다 — 그건 {@link Background}가 담당한다.
 */
public enum Motion {
    /** 가로로 흐르듯 이동(앞 화면과 겹침 있음) → dx를 재서 새로 드러난 부분만 누적. */
    SCROLL("스크롤"),
    /** 한 화면이 멈췄다가 다음으로 넘어감(겹침 거의 없음) → 화면이 바뀌면 통째로 새 행. */
    CUT("화면 전환");

    public final String label;

    Motion(String label) { this.label = label; }

    @Override public String toString() { return label; }
}
