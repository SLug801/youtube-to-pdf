---
description: 변환 결과(누락/중복/흐림)에 맞춰 FrameExtractor 튜닝 상수 조정안 제시
argument-hint: [증상: 누락 | 중복 | 초반흰색 | 옅음]
allowed-tools: Read, Edit, Grep
---

증상: $ARGUMENTS

[FrameExtractor.java](src/main/java/com/sheetmusic/FrameExtractor.java) 상단의 튜닝 상수를
읽고, 아래 표에 따라 조정안을 제시한다. **바로 고치지 말고** 변경할 상수·현재값·제안값을
먼저 보여주고 사용자 확인을 받은 뒤 Edit 한다.

| 증상 | 조정 |
|---|---|
| 누락이 남음 | `MARGIN` ↓ (예: 0.08), `TPL_RATIO` ↓ |
| 중복이 생김 | `MARGIN` ↑ (예: 0.18) |
| 초반이 하얗게 | `SEED_REFRESH_SCORE` ↓, 출력 CLAHE/게인 조정 |
| 출력 표기가 옅음/진함 | `cleanForOutput`의 대비 게인(`Scalar(3.0)`) |

조정 후에는 `/convert` 로 같은 영상을 재변환해 로그의 `dx`/`score`로 효과를 검증하라고 안내한다.
