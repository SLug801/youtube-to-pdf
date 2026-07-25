from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from ytpdf_core.preview import PreviewImage


def generate_preview(
    url: str,
    output_directory: Path,
    *,
    at_seconds: float,
    yt_dlp_path: Path | None,
    logger: Callable[[str], None],
) -> PreviewImage:
    # OpenCV 로드는 패키징 환경에서 오래 걸리므로 API 기동이 아닌 첫 프리뷰 요청에서 수행한다.
    from ytpdf_core.preview import create_preview

    return create_preview(
        url,
        output_directory,
        at_seconds=at_seconds,
        yt_dlp_path=yt_dlp_path,
        logger=logger,
    )
