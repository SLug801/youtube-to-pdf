package com.sheetmusic.vision;

/**
 * {@link FrameExtractor} 스캔·스티칭 알고리즘의 튜닝 상수 모음.
 * 값마다 실측 기반의 조정 근거를 주석으로 남겨 둔다. 알고리즘 코드(FrameExtractor)와
 * 분리해 가독성을 높이며, FrameExtractor는 {@code import static}로 이름 그대로 참조한다.
 *
 * (이미지 처리 임계는 {@link SheetImageOps}, 영상 구간 테스트 토글은 FrameExtractor 내부에 있다.)
 */
final class ScanParams {

    private ScanParams() {}   // 상수 홀더 — 인스턴스화 금지

    // ── 스캔/스티칭 파라미터 ────────────────────────────────────────────────
    static final int    SCAN_FPS     = 20;      // (was 10) 초당 검사 프레임. 높일수록 큰 점프
                                                //   스크롤을 작은 스텝 여러 개로 쪼개 봐서, 한 스텝에
                                                //   들어가는 반복 패턴이 줄고 매칭 정확↑(중복·누락 동시 감소).
                                                //   대신 처리 시간이 비례해 늘어난다.
    static final int    SCAN_FPS_OPAQUE = 6;    // 불투명(페이지 넘김)은 페이지가 몇 초씩 정지하므로
                                                //   초당 6회만 검사해도 전환을 놓치지 않는다. 매칭 연산을
                                                //   1/3 이하로 줄여 속도↑(2샘플=약 0.33s면 새 페이지 확정).
    static final double TPL_RATIO    = 0.15;    // (was 0.12) 매칭 템플릿 폭(ROI 폭 대비).
                                                //   크게 잡을수록 템플릿이 더 고유해져
                                                //   반복 패턴 오매칭(중복·이상 절단)이 준다.
                                                //   대신 검출 가능한 최대 점프는 (roiW-tw)로 줄어듦.
    static final int    TPL_INSET    = 0;       // 템플릿을 현재 프레임 왼쪽에서 떼는 위치
    static final double MIN_SCORE    = 0.40;    // (was 0.35) 겹침 신뢰 임계. 높을수록 약하고
                                                //   모호한 매칭으로 dx를 잘못 잡는 일이 준다.
    static final double MARGIN       = 0.15;    // (was 0.12) peak−zero 마진. 높을수록 실제
                                                //   스크롤만 채택 → 미세 false 스크롤(중복 슬라이버) 차단.
    static final double SEED_REFRESH_SCORE = 0.70; // (was 0.80) 첫 스크롤 전 동일화면 판정(시드 갱신).
                                                   //   낮출수록 페이드인 중 더 또렷한 프레임으로 시드 교체
                                                   //   → 첫 화면이 하얗게 나오는 문제 완화.
    static final double NEWPAGE_MAX_SCORE   = 0.30; // 이보다 매칭이 낮아야 "새 페이지" 후보
    static final double NEWPAGE_OVERLAP_SCORE = 0.55; // 새 페이지 통째 붙이기 전, raw 회색조로 확정 화면과
                                                    //   겹침 재확인하는 임계. 이 이상이면 겹침으로 보고 trim.
                                                    //   feature가 반복 패턴에서 실패해 생긴 통째-중복을 잡음.
    static final double STABLE_SCORE = 0.75;    // 정지(동일 화면) 판정 임계
    static final int    MIN_SHIFT    = 4;       // (was 3) 이보다 작은 dx는 정지로 간주.
                                                //   지터성 미세 중복을 차단(FPS↑로 스텝이 작아져 4 유지).
    static final double CONTENT_MIN  = 0.004;   // 인트로(빈 화면) 판별 임계

    // ── 2-밴드 합의(consensus) 검증 ──────────────────────────────────────────
    // 현재 프레임의 서로 다른 두 위치 밴드로 각각 dx를 재서, 둘이 일치할 때만 스크롤로 인정한다.
    // 강체 이동(진짜 스크롤)은 두 밴드 dx가 같지만, 빈 마디·반복 패턴의 주기적 오매칭은
    // 두 밴드가 서로 다른 오프셋에 꽂혀 불일치 → 거부(중복 append 차단).
    static final double SECOND_BAND_RATIO = 0.30; // 둘째 밴드를 떼는 위치(ROI 폭 대비)
    static final int    DX_AGREE_TOL      = 6;    // 두 밴드 dx 허용 오차(px) — 이내면 일치로 봄

    // ── 불투명(OPAQUE) 페이지 스냅샷 스티칭 임계 ──────────────────────────────
    // 페이지 스냅샷(불투명): 확정 페이지와의 dx=0 상관이 SAME_PAGE 미만이면 '전환됨',
    // 직전 프레임과의 상관이 STABLE_PAGE 이상이면 '안정(전환 끝남)'으로 보고 새 행을 붙인다.
    // simConf 이중분포: ~0.70은 같은 페이지에서 재생 하이라이트/플레이헤드만 이동, ~0.50은 진짜 플립.
    // 둘 사이(0.62)로 갈라 하이라이트 이동은 무시하고 실제 페이지 전환만 새 행으로 커밋한다.
    static final double OPAQUE_SAME_PAGE   = 0.62; // 이 미만이면 확정 페이지와 다른 페이지(플립)
    static final double OPAQUE_STABLE_PAGE = 0.90; // 이 이상이면 직전과 같아 안정(미안정 전환 배제)
    // [A안] 새 페이지를 통째 붙이기 전, 확정 페이지와 겹친 만큼만 잘라 중복을 없앤다.
    //   누락이 더 치명적이므로 반투명(MIN_SCORE 0.40)보다 빡빡하게 — "확실할 때만" trim, 아니면 통째.
    static final double OPAQUE_TRIM_SCORE  = 0.60; // 겹침 신뢰 임계(이 미만이면 못 믿어 통째 붙임)
}
