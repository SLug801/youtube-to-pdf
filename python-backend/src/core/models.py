from __future__ import annotations

import math
from dataclasses import dataclass
from enum import StrEnum


class Background(StrEnum):
    TRANSLUCENT = "translucent"
    OPAQUE = "opaque"

    @property
    def label(self) -> str:
        return "반투명" if self is self.TRANSLUCENT else "불투명"


class Motion(StrEnum):
    SCROLL = "scroll"
    CUT = "cut"

    @property
    def label(self) -> str:
        return "스크롤" if self is self.SCROLL else "화면 전환"


@dataclass(frozen=True, slots=True)
class RoiConfig:
    top_ratio: float = 0.70
    bottom_ratio: float = 1.00
    left_ratio: float = 0.00
    right_ratio: float = 1.00

    def __post_init__(self) -> None:
        values = (
            self.top_ratio,
            self.bottom_ratio,
            self.left_ratio,
            self.right_ratio,
        )
        if not all(math.isfinite(value) for value in values):
            raise ValueError("ROI 값은 유한한 숫자여야 합니다.")
        if not 0 <= self.top_ratio < self.bottom_ratio <= 1:
            raise ValueError("ROI 상하 비율은 0 ≤ top < bottom ≤ 1이어야 합니다.")
        if not 0 <= self.left_ratio < self.right_ratio <= 1:
            raise ValueError("ROI 좌우 비율은 0 ≤ left < right ≤ 1이어야 합니다.")

    @classmethod
    def parse(cls, value: str) -> RoiConfig:
        parts = [float(part.strip()) for part in value.split(",")]
        if len(parts) != 4:
            raise ValueError("ROI 형식: top,bottom,left,right")
        return cls(*parts)

    def bounds(self, width: int, height: int) -> tuple[int, int, int, int]:
        y1 = _clamp(int(height * self.top_ratio), 0, height - 1)
        y2 = _clamp(int(height * self.bottom_ratio), y1 + 1, height)
        x1 = _clamp(int(width * self.left_ratio), 0, width - 1)
        x2 = _clamp(int(width * self.right_ratio), x1 + 1, width)
        return x1, y1, x2, y2

    def __str__(self) -> str:
        return (
            f"상단{self.top_ratio * 100:.0f}%~하단{self.bottom_ratio * 100:.0f}%, "
            f"좌{self.left_ratio * 100:.0f}%~우{self.right_ratio * 100:.0f}%"
        )


def _clamp(value: int, minimum: int, maximum: int) -> int:
    return max(minimum, min(maximum, value))

