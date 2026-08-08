from __future__ import annotations

from typing import Any

import cv2
import numpy as np
from numpy.typing import NDArray

from .models import Background

Image = NDArray[Any]


class SheetImageOps:
    NOISE_FLOOR = 45
    NOISE_MIN_AREA = 8
    OPAQUE_BLOCK = 31
    OPAQUE_C = 12

    def __init__(self, background: Background) -> None:
        self.background = background

    def feature_image(self, color: Image) -> Image:
        if self.background is Background.OPAQUE:
            binary = self._binarize_opaque(color)
        else:
            gray = cv2.cvtColor(color, cv2.COLOR_BGR2GRAY)
            if float(np.mean(gray)) < 100:
                gray = cv2.bitwise_not(gray)
            kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9))
            blackhat = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT, kernel)
            _, binary = cv2.threshold(
                blackhat,
                0,
                255,
                cv2.THRESH_BINARY + cv2.THRESH_OTSU,
            )

        kernel_width = max(15, binary.shape[1] // 3)
        horizontal_kernel = cv2.getStructuringElement(
            cv2.MORPH_RECT,
            (kernel_width, 1),
        )
        lines = cv2.morphologyEx(binary, cv2.MORPH_OPEN, horizontal_kernel)
        return cv2.subtract(binary, lines)

    def clean_for_output(self, color: Image) -> Image:
        if self.background is Background.OPAQUE:
            marks = self._binarize_opaque(color)
        else:
            gray = cv2.cvtColor(color, cv2.COLOR_BGR2GRAY)
            if float(np.mean(gray)) < 100:
                gray = cv2.bitwise_not(gray)
            equalized = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(gray)
            kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9))
            marks = cv2.morphologyEx(equalized, cv2.MORPH_BLACKHAT, kernel)
            marks = np.clip(marks.astype(np.float32) * 3.0, 0, 255).astype(np.uint8)

        marks = self._denoise_marks(marks)
        inverted = cv2.bitwise_not(marks)
        return cv2.cvtColor(inverted, cv2.COLOR_GRAY2BGR)

    def has_sheet_structure(self, color: Image) -> bool:
        gray = cv2.cvtColor(color, cv2.COLOR_BGR2GRAY)
        if float(np.mean(gray)) < 110:
            gray = cv2.bitwise_not(gray)
        ink = cv2.adaptiveThreshold(
            gray,
            255,
            cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
            cv2.THRESH_BINARY_INV,
            self.OPAQUE_BLOCK,
            self.OPAQUE_C,
        )
        minimum_length = max(30, color.shape[1] // 3)
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (minimum_length, 1))
        horizontal = cv2.morphologyEx(ink, cv2.MORPH_OPEN, kernel)
        minimum_pixels = max(20, color.shape[1] // 4)
        line_rows = np.count_nonzero(horizontal, axis=1) >= minimum_pixels
        starts = line_rows & np.concatenate(([True], ~line_rows[:-1]))
        return int(np.count_nonzero(starts)) >= 2

    def _binarize_opaque(self, color: Image) -> Image:
        gray = cv2.cvtColor(color, cv2.COLOR_BGR2GRAY)
        if float(np.mean(gray)) < 110:
            gray = cv2.bitwise_not(gray)
        return cv2.adaptiveThreshold(
            gray,
            255,
            cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
            cv2.THRESH_BINARY_INV,
            self.OPAQUE_BLOCK,
            self.OPAQUE_C,
        )

    def _denoise_marks(self, marks: Image) -> Image:
        _, floored = cv2.threshold(marks, self.NOISE_FLOOR, 0, cv2.THRESH_TOZERO)
        if self.NOISE_MIN_AREA <= 1:
            return floored
        binary = np.where(floored > 0, 255, 0).astype(np.uint8)
        count, labels, stats, _ = cv2.connectedComponentsWithStats(binary, connectivity=8)
        keep = np.zeros(count, dtype=np.uint8)
        if count > 1:
            keep[1:] = (
                stats[1:, cv2.CC_STAT_AREA] >= self.NOISE_MIN_AREA
            ).astype(np.uint8)
        return np.where(keep[labels] > 0, floored, 0).astype(np.uint8)
