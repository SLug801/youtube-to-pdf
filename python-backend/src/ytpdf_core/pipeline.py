from __future__ import annotations

from collections.abc import Callable
from pathlib import Path

from ytpdf_core.downloader import download_video
from ytpdf_core.extractor import FrameExtractor
from ytpdf_core.models import Background, Motion, RoiConfig
from ytpdf_core.pdf_builder import build_pdf

Logger = Callable[[str], None]


def convert_url(
    url: str,
    output_directory: Path,
    *,
    start_seconds: float = 0,
    end_seconds: float = 0,
    roi: RoiConfig | None = None,
    background: Background = Background.TRANSLUCENT,
    motion: Motion = Motion.SCROLL,
    yt_dlp_path: Path | None = None,
    logger: Logger,
) -> Path:
    work_directory = output_directory.resolve() / "sheet_01"
    frames_directory = work_directory / "captured_frames"
    pdf_path = work_directory / "sheet_01.pdf"
    work_directory.mkdir(parents=True, exist_ok=True)

    logger("[엔진] Python OpenCV 변환 엔진")
    video_path = download_video(url, work_directory, logger, yt_dlp_path)
    frames = FrameExtractor(
        roi=roi,
        background=background,
        motion=motion,
        start_seconds=start_seconds,
        end_seconds=end_seconds,
        logger=logger,
    ).extract(video_path, frames_directory)
    if not frames:
        raise RuntimeError("캡처된 프레임이 없습니다. ROI 설정을 확인하세요.")
    build_pdf(frames, pdf_path, logger)
    video_path.unlink(missing_ok=True)
    logger(f"[정리] 영상 파일 삭제됨 → 결과물: {work_directory}/")
    return pdf_path

