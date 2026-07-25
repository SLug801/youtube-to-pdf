from __future__ import annotations

import math
import time
from collections.abc import Callable
from pathlib import Path

import cv2
import numpy as np
from numpy.typing import NDArray

from ytpdf_core.image_ops import Image, SheetImageOps
from ytpdf_core.models import Background, Motion, RoiConfig
from ytpdf_core.params import (
    CONTENT_MIN,
    CUT_SAME_SCREEN,
    CUT_STABLE,
    CUT_TRIM_SCORE,
    DX_AGREE_TOL,
    MARGIN,
    MIN_SCORE,
    MIN_SHIFT,
    NEWPAGE_MAX_SCORE,
    NEWPAGE_OVERLAP_SCORE,
    SCAN_FPS,
    SCAN_FPS_CUT,
    SECOND_BAND_RATIO,
    SEED_REFRESH_SCORE,
    STABLE_SCORE,
    TPL_INSET,
    TPL_RATIO,
)

Logger = Callable[[str], None]


def _console(message: str) -> None:
    print(message, flush=True)


class StreamingRowWriter:
    MIN_LAST_ROW_WIDTH = 40

    def __init__(
        self,
        chunk_width: int,
        row_height: int,
        output_directory: Path,
        background: Background,
        image_ops: SheetImageOps,
        logger: Logger,
    ) -> None:
        self.chunk_width = chunk_width
        self.row_height = row_height
        self.output_directory = output_directory
        self.background = background
        self.image_ops = image_ops
        self.logger = logger
        self.saved: list[Path] = []
        self.pending: Image | None = None
        self.finished = False

    def seed(self, frame: Image) -> None:
        self.pending = frame.copy()

    def replace_seed(self, frame: Image) -> None:
        if self.saved or self.pending is None or self.pending.shape[1] != self.chunk_width:
            raise RuntimeError("이미 확정된 악보 줄의 시드는 교체할 수 없습니다.")
        self.seed(frame)

    def append(self, source: Image) -> None:
        if self.finished:
            raise RuntimeError("이미 완료된 스트리밍 출력입니다.")
        if source.size == 0:
            return
        self.pending = (
            source.copy()
            if self.pending is None
            else np.ascontiguousarray(np.hstack((self.pending, source)))
        )
        while self.pending.shape[1] >= self.chunk_width:
            row = self.pending[:, : self.chunk_width].copy()
            remaining = self.pending[:, self.chunk_width :].copy()
            self.pending = remaining if remaining.size else None
            self._save_row(row)
            if self.pending is None:
                break

    def append_slice(self, source: Image, x: int, width: int) -> None:
        self.append(source[:, x : x + width])

    def finish(self) -> list[Path]:
        if self.finished:
            return list(self.saved)
        self.finished = True
        if self.pending is not None and self.pending.shape[1] >= self.MIN_LAST_ROW_WIDTH:
            padded = np.full(
                (self.row_height, self.chunk_width, 3),
                255,
                dtype=np.uint8,
            )
            padded[:, : self.pending.shape[1]] = self.pending
            self._save_row(padded)
        self.pending = None
        return list(self.saved)

    def _save_row(self, raw_row: Image) -> None:
        output = (
            raw_row
            if self.background is Background.OPAQUE
            else self.image_ops.clean_for_output(raw_row)
        )
        path = self.output_directory / f"frame_{len(self.saved):04d}.jpg"
        if not cv2.imwrite(str(path), output):
            raise OSError(f"악보 이미지 저장 실패: {path}")
        self.saved.append(path)
        self.logger(f"FRAME_SAVED:{path.resolve()}")


class FrameExtractor:
    def __init__(
        self,
        roi: RoiConfig | None = None,
        background: Background = Background.TRANSLUCENT,
        motion: Motion = Motion.SCROLL,
        start_seconds: float = 0,
        end_seconds: float = 0,
        logger: Logger | None = None,
    ) -> None:
        if not math.isfinite(start_seconds) or start_seconds < 0:
            raise ValueError("추출 시작 시각은 0초 이상이어야 합니다.")
        if not math.isfinite(end_seconds) or end_seconds < 0:
            raise ValueError("추출 종료 시각은 0초 이상이어야 합니다.")
        if end_seconds > 0 and end_seconds <= start_seconds:
            raise ValueError("추출 종료 시각은 시작 시각보다 뒤여야 합니다.")
        self.roi = roi or RoiConfig()
        self.background = background
        self.motion = motion
        self.start_seconds = start_seconds
        self.end_seconds = end_seconds
        self.logger = logger or _console
        self.image_ops = SheetImageOps(background)

    def extract(self, video_path: Path, output_directory: Path) -> list[Path]:
        output_directory.mkdir(parents=True, exist_ok=True)
        capture = cv2.VideoCapture(str(video_path))
        if not capture.isOpened():
            raise OSError(f"영상을 열 수 없습니다: {video_path}")
        try:
            return self._extract_opened(capture, output_directory)
        finally:
            capture.release()

    def _extract_opened(
        self,
        capture: cv2.VideoCapture,
        output_directory: Path,
    ) -> list[Path]:
        fps = float(capture.get(cv2.CAP_PROP_FPS))
        total_frames = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
        duration = total_frames / fps if fps > 0 and total_frames > 0 else 0.0
        scan_end = min(self.end_seconds, duration) if self.end_seconds > 0 and duration > 0 else (
            self.end_seconds or duration
        )
        if scan_end > 0 and self.start_seconds >= scan_end:
            raise ValueError(
                f"추출 시작 시각({self.start_seconds:.1f}초)이 "
                f"영상 길이({duration:.1f}초)보다 깁니다."
            )

        width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))
        if width <= 0 or height <= 0:
            raise OSError("영상 해상도를 확인할 수 없습니다.")
        x1, y1, x2, y2 = self.roi.bounds(width, height)
        roi_width, roi_height = x2 - x1, y2 - y1
        template_width = _clamp(int(roi_width * TPL_RATIO), 8, roi_width - 1)
        scan_fps = SCAN_FPS_CUT if self.motion is Motion.CUT else SCAN_FPS
        frame_skip = max(1, math.floor((fps if fps > 0 else scan_fps) / scan_fps + 0.5))
        scan_length = max(0.0, scan_end - self.start_seconds) if scan_end > 0 else 0.0

        self.logger(
            f"[시작] 해상도={width}x{height} | FPS={fps:.1f} | 길이={duration:.1f}s "
            f"| 모드=Python 전폭매칭(검사{scan_fps}fps, {frame_skip}프레임마다)"
        )
        self.logger(
            f"[설정] 배경={self.background.label} | 진행={self.motion.label} | "
            f"ROI={self.roi} | 템플릿={template_width}px | "
            f"임계 match={MIN_SCORE:.2f} stable={STABLE_SCORE:.2f}"
        )
        end_label = f"{scan_end:.1f}초" if self.end_seconds > 0 else "영상 끝"
        self.logger(f"[구간] {self.start_seconds:.1f}초 ~ {end_label}")
        if self.start_seconds > 0:
            capture.set(cv2.CAP_PROP_POS_MSEC, self.start_seconds * 1000)

        writer = StreamingRowWriter(
            roi_width,
            roi_height,
            output_directory,
            self.background,
            self.image_ops,
            self.logger,
        )
        confirmed_feature: Image | None = None
        confirmed_color: Image | None = None
        last_feature: Image | None = None
        canvas_width = 0
        started = False
        scrolled = False
        scroll_count = page_count = static_count = reject_count = trim_count = 0
        second_inset = _clamp(
            int(roi_width * SECOND_BAND_RATIO),
            template_width,
            max(template_width, roi_width - 2 * template_width),
        )
        second_reach = roi_width - template_width - second_inset
        grabbed_frames = sample_index = 0
        started_at = time.monotonic()
        last_dx = 0
        last_score = 0.0

        while True:
            ok, frame = capture.read()
            if not ok:
                break
            current_seconds = float(capture.get(cv2.CAP_PROP_POS_MSEC)) / 1000
            if current_seconds <= 0 and fps > 0:
                current_seconds = max(0.0, (capture.get(cv2.CAP_PROP_POS_FRAMES) - 1) / fps)
            if current_seconds < self.start_seconds:
                continue
            if scan_end > 0 and current_seconds > scan_end:
                break
            should_sample = grabbed_frames % frame_skip == 0
            grabbed_frames += 1
            if not should_sample:
                continue

            roi_color: Image = np.ascontiguousarray(frame[y1:y2, x1:x2])
            feature = self.image_ops.feature_image(roi_color)

            if not started:
                content_ratio = float(np.count_nonzero(feature)) / feature.size
                sheet_frame = (
                    self.motion is not Motion.CUT
                    or self.image_ops.has_sheet_structure(roi_color)
                )
                if content_ratio < CONTENT_MIN or not sheet_frame:
                    sample_index += 1
                    continue
                writer.seed(roi_color)
                confirmed_feature = feature.copy()
                confirmed_color = roi_color.copy()
                last_feature = feature.copy()
                canvas_width = roi_width
                started = True
                self.logger(
                    f"[콘텐츠 시작] t={current_seconds:.1f}s (시드 {roi_width}px)"
                )
                sample_index += 1
                continue

            assert confirmed_feature is not None
            assert confirmed_color is not None
            assert last_feature is not None

            if self.motion is Motion.CUT:
                similarity_confirmed = _same_screen_score(confirmed_feature, feature)
                similarity_last = _same_screen_score(last_feature, feature)
                last_score = similarity_confirmed
                changed = similarity_confirmed < CUT_SAME_SCREEN
                stable = similarity_last >= CUT_STABLE
                sheet_frame = (
                    not changed
                    or not stable
                    or self.image_ops.has_sheet_structure(roi_color)
                )
                if changed and stable and sheet_frame:
                    dx, score, zero = _match_offset(
                        confirmed_feature,
                        feature,
                        template_width,
                        TPL_INSET,
                    )
                    last_dx, last_score = dx, score
                    trust_overlap = (
                        MIN_SHIFT <= dx <= roi_width - template_width
                        and score >= CUT_TRIM_SCORE
                        and score - zero >= MARGIN
                    )
                    if trust_overlap and dx <= second_reach:
                        dx2, score2, _ = _match_offset(
                            confirmed_feature,
                            feature,
                            template_width,
                            second_inset,
                        )
                        if score2 >= CUT_TRIM_SCORE and abs(dx - dx2) > DX_AGREE_TOL:
                            trust_overlap = False
                    appended_width = dx if trust_overlap else roi_width
                    if trust_overlap:
                        writer.append_slice(roi_color, roi_width - dx, dx)
                        trim_count += 1
                    else:
                        writer.append(roi_color)
                    confirmed_feature = feature.copy()
                    canvas_width += appended_width
                    page_count += 1
                    scrolled = True
                    trim_message = (
                        f" | trim={roi_width - appended_width}px" if trust_overlap else ""
                    )
                    self.logger(
                        f"[화면 확정] t={current_seconds:.1f}s | "
                        f"confirmed={similarity_confirmed:.2f} "
                        f"stable={similarity_last:.2f}{trim_message}"
                    )
                elif not scrolled and not changed:
                    writer.replace_seed(roi_color)
                    confirmed_feature = feature.copy()
                    static_count += 1
                else:
                    static_count += 1
            else:
                dx, score, zero = _match_offset(
                    confirmed_feature,
                    feature,
                    template_width,
                    TPL_INSET,
                )
                last_dx, last_score = dx, score
                is_scroll = (
                    MIN_SHIFT <= dx <= roi_width - template_width
                    and score >= MIN_SCORE
                    and score - zero >= MARGIN
                )
                if is_scroll and dx <= second_reach:
                    dx2, score2, _ = _match_offset(
                        confirmed_feature,
                        feature,
                        template_width,
                        second_inset,
                    )
                    if score2 >= MIN_SCORE and abs(dx - dx2) > DX_AGREE_TOL:
                        is_scroll = False
                        reject_count += 1
                        self.logger(
                            f"[2밴드 거부] t={current_seconds:.1f}s dx1={dx} dx2={dx2} "
                            f"(불일치>{DX_AGREE_TOL}px) → 스크롤 기각"
                        )

                if is_scroll:
                    writer.append_slice(roi_color, roi_width - dx, dx)
                    confirmed_feature = feature.copy()
                    confirmed_color = roi_color.copy()
                    canvas_width += dx
                    scroll_count += 1
                    scrolled = True
                elif not scrolled and zero >= SEED_REFRESH_SCORE:
                    writer.replace_seed(roi_color)
                    confirmed_feature = feature.copy()
                    confirmed_color = roi_color.copy()
                    static_count += 1
                elif score < NEWPAGE_MAX_SCORE:
                    _, _, last_zero = _match_offset(
                        last_feature,
                        feature,
                        template_width,
                        TPL_INSET,
                    )
                    if last_zero >= STABLE_SCORE:
                        gray_dx, gray_score, _ = _match_offset_gray(
                            confirmed_color,
                            roi_color,
                            template_width,
                            TPL_INSET,
                        )
                        if (
                            gray_score >= NEWPAGE_OVERLAP_SCORE
                            and 0 <= gray_dx <= roi_width - template_width
                        ):
                            if gray_dx >= MIN_SHIFT:
                                writer.append_slice(
                                    roi_color,
                                    roi_width - gray_dx,
                                    gray_dx,
                                )
                                confirmed_feature = feature.copy()
                                confirmed_color = roi_color.copy()
                                canvas_width += gray_dx
                                scroll_count += 1
                                scrolled = True
                            else:
                                static_count += 1
                        else:
                            writer.append(roi_color)
                            confirmed_feature = feature.copy()
                            confirmed_color = roi_color.copy()
                            canvas_width += roi_width
                            page_count += 1
                            scrolled = True
                    else:
                        static_count += 1
                else:
                    static_count += 1

            last_feature = feature.copy()
            if sample_index > 0 and sample_index % (scan_fps * 8) == 0:
                percentage = (
                    max(0.0, current_seconds - self.start_seconds) / scan_length * 100
                    if scan_length > 0
                    else 0.0
                )
                elapsed = int(time.monotonic() - started_at)
                self.logger(
                    f"  진행 {min(100, percentage):.0f}% ({elapsed}s) 폭={canvas_width}px "
                    f"| dx={last_dx} score={last_score:.2f} | "
                    f"{_counts(scroll_count, page_count, static_count, reject_count, trim_count)}"
                )
            sample_index += 1

        if not started:
            self.logger("[경고] 콘텐츠를 찾지 못했습니다.")
            return []
        saved = writer.finish()
        self.logger(
            f"[스트리밍 스티칭] 누적 폭={canvas_width}px 높이={roi_height}px "
            f"| 임시 버퍼≤{roi_width * 2}px | "
            f"{_counts(scroll_count, page_count, static_count, reject_count, trim_count)}"
        )
        self.logger(f"[완료] 총 {len(saved)}줄 생성")
        return saved


def _match_offset(
    reference: Image,
    current: Image,
    template_width: int,
    inset: int,
) -> tuple[int, float, float]:
    current_width = current.shape[1]
    template_x = _clamp(inset, 0, max(0, current_width - template_width))
    template = current[:, template_x : template_x + template_width]
    result = cv2.matchTemplate(reference, template, cv2.TM_CCOEFF_NORMED)
    _, maximum, _, location = cv2.minMaxLoc(result)
    zero = float(result[0, template_x])
    return location[0] - template_x, float(maximum), zero


def _match_offset_gray(
    reference: Image,
    current: Image,
    template_width: int,
    inset: int,
) -> tuple[int, float, float]:
    reference_gray = cv2.cvtColor(reference, cv2.COLOR_BGR2GRAY)
    current_gray = cv2.cvtColor(current, cv2.COLOR_BGR2GRAY)
    return _match_offset(reference_gray, current_gray, template_width, inset)


def _same_screen_score(confirmed: Image, current: Image) -> float:
    if confirmed.shape != current.shape:
        return -1.0
    result: NDArray[np.float32] = cv2.matchTemplate(  # type: ignore[assignment]
        confirmed,
        current,
        cv2.TM_CCOEFF_NORMED,
    )
    return float(result[0, 0])


def _clamp(value: int, minimum: int, maximum: int) -> int:
    return max(minimum, min(maximum, value))


def _counts(scroll: int, page: int, static: int, reject: int, trim: int) -> str:
    values = (
        ("스크롤", scroll),
        ("페이지", page),
        ("정지", static),
        ("합의거부", reject),
        ("트림", trim),
    )
    return " ".join(f"{label}{count}" for label, count in values if count > 0) or "-"
