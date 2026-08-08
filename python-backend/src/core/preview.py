from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

import cv2

from .downloader import download_video

Logger = Callable[[str], None]


@dataclass(frozen=True, slots=True)
class PreviewImage:
    content: bytes
    width: int
    height: int
    timestamp_seconds: float


def create_preview(
    url: str,
    output_directory: Path,
    *,
    at_seconds: float = 5,
    max_width: int = 1600,
    yt_dlp_path: Path | None = None,
    logger: Logger,
) -> PreviewImage:
    work_directory = output_directory.resolve() / "sheet_01"
    video_path = download_video(url, work_directory, logger, yt_dlp_path)
    capture = cv2.VideoCapture(str(video_path))
    if not capture.isOpened():
        raise OSError(f"프리뷰용 영상을 열 수 없습니다: {video_path}")

    try:
        fps = float(capture.get(cv2.CAP_PROP_FPS))
        frame_count = float(capture.get(cv2.CAP_PROP_FRAME_COUNT))
        duration = frame_count / fps if fps > 0 and frame_count > 0 else 0
        timestamp = max(0.0, at_seconds)
        if duration > 0:
            timestamp = min(timestamp, max(0.0, duration - (1 / max(fps, 1))))

        capture.set(cv2.CAP_PROP_POS_MSEC, timestamp * 1000)
        success, frame = capture.read()
        if not success or frame is None:
            capture.set(cv2.CAP_PROP_POS_FRAMES, 0)
            success, frame = capture.read()
            timestamp = 0
        if not success or frame is None:
            raise OSError("영상에서 프리뷰 프레임을 읽지 못했습니다.")

        height, width = frame.shape[:2]
        if width > max_width:
            scale = max_width / width
            width = max_width
            height = max(1, round(height * scale))
            frame = cv2.resize(frame, (width, height), interpolation=cv2.INTER_AREA)

        encoded, buffer = cv2.imencode(
            ".jpg",
            frame,
            [cv2.IMWRITE_JPEG_QUALITY, 88],
        )
        if not encoded:
            raise OSError("프리뷰 이미지를 JPEG로 변환하지 못했습니다.")
        logger(f"[프리뷰] {timestamp:.2f}초 프레임 준비 ({width}x{height})")
        return PreviewImage(
            content=buffer.tobytes(),
            width=width,
            height=height,
            timestamp_seconds=timestamp,
        )
    finally:
        capture.release()
