from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np
from PIL import Image

from ytpdf_core.extractor import FrameExtractor, _match_offset
from ytpdf_core.image_ops import SheetImageOps
from ytpdf_core.models import Background, Motion, RoiConfig
from ytpdf_core.pdf_builder import build_pdf


def make_score_canvas(width: int, height: int, page: int = 0) -> np.ndarray:
    canvas = np.full((height, width, 3), 255, dtype=np.uint8)
    for baseline in (25, 65):
        for delta in (0, 6, 12):
            cv2.line(canvas, (0, baseline + delta), (width - 1, baseline + delta), (20, 20, 20), 1)
    for index, x in enumerate(range(14, width, 41)):
        top = 12 + (index * 7 + page * 11) % 45
        cv2.line(canvas, (x, top), (x, min(height - 8, top + 32)), (0, 0, 0), 2)
        cv2.circle(canvas, (x + 7, min(height - 8, top + 12)), 3 + index % 3, (0, 0, 0), -1)
        cv2.putText(
            canvas,
            str((index + page * 3) % 10),
            (x + 10, min(height - 5, top + 28)),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.35,
            (0, 0, 0),
            1,
            cv2.LINE_AA,
        )
    return canvas


def write_video(path: Path, frames: list[np.ndarray], fps: float = 20) -> None:
    height, width = frames[0].shape[:2]
    writer = cv2.VideoWriter(
        str(path),
        cv2.VideoWriter_fourcc(*"MJPG"),
        fps,
        (width, height),
    )
    assert writer.isOpened()
    for frame in frames:
        writer.write(frame)
    writer.release()


def test_match_offset_finds_newly_revealed_width() -> None:
    canvas = make_score_canvas(420, 100)
    reference = canvas[:, :240]
    current = canvas[:, 24:264]
    ops = SheetImageOps(Background.OPAQUE)

    dx, score, zero = _match_offset(
        ops.feature_image(reference),
        ops.feature_image(current),
        36,
        0,
    )

    assert abs(dx - 24) <= 1
    assert score > 0.9
    assert score - zero > 0.15


def test_scroll_video_is_stitched_into_streaming_rows(tmp_path: Path) -> None:
    canvas = make_score_canvas(520, 100)
    positions = [0] * 6 + [position for position in range(12, 181, 12) for _ in range(3)]
    frames = [canvas[:, position : position + 240].copy() for position in positions]
    video = tmp_path / "scroll.avi"
    write_video(video, frames)

    saved = FrameExtractor(
        roi=RoiConfig(0, 1, 0, 1),
        background=Background.OPAQUE,
        motion=Motion.SCROLL,
        logger=lambda _message: None,
    ).extract(video, tmp_path / "scroll-frames")

    assert len(saved) >= 2
    with Image.open(saved[0]) as image:
        assert image.size == (240, 100)


def test_cut_video_commits_stable_pages_and_respects_time_range(tmp_path: Path) -> None:
    first = make_score_canvas(240, 100, page=0)
    second = make_score_canvas(240, 100, page=4)
    video = tmp_path / "cut.avi"
    write_video(video, [first.copy() for _ in range(20)] + [second.copy() for _ in range(20)])

    all_pages = FrameExtractor(
        roi=RoiConfig(0, 1, 0, 1),
        background=Background.OPAQUE,
        motion=Motion.CUT,
        logger=lambda _message: None,
    ).extract(video, tmp_path / "cut-all")
    ranged = FrameExtractor(
        roi=RoiConfig(0, 1, 0, 1),
        background=Background.OPAQUE,
        motion=Motion.CUT,
        start_seconds=1.0,
        end_seconds=1.9,
        logger=lambda _message: None,
    ).extract(video, tmp_path / "cut-range")

    assert len(all_pages) == 2
    assert len(ranged) == 1


def test_pdf_builder_creates_a4_document(tmp_path: Path) -> None:
    images: list[Path] = []
    for index in range(3):
        path = tmp_path / f"frame_{index:04d}.jpg"
        cv2.imwrite(str(path), make_score_canvas(240, 100, page=index))
        images.append(path)
    output = tmp_path / "result.pdf"

    build_pdf(images, output, logger=lambda _message: None)

    assert output.read_bytes().startswith(b"%PDF")

