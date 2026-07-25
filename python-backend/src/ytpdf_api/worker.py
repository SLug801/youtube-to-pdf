from __future__ import annotations

import argparse
import os
from pathlib import Path

from ytpdf_api.schemas import parse_time_seconds
from ytpdf_core.models import Background, Motion, RoiConfig
from ytpdf_core.pipeline import convert_url


def run_worker(arguments: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Python 영상 처리 Worker")
    parser.add_argument("--output-directory", required=True, type=Path)
    parser.add_argument("--start")
    parser.add_argument("--end")
    parser.add_argument("--roi", default="0.70,1.00,0.00,1.00")
    parser.add_argument(
        "--background",
        choices=[item.value for item in Background],
        default=Background.TRANSLUCENT.value,
    )
    parser.add_argument(
        "--motion",
        choices=[item.value for item in Motion],
        default=Motion.SCROLL.value,
    )
    parser.add_argument("url")
    options = parser.parse_args(arguments)

    start_seconds = parse_time_seconds(options.start) if options.start else 0
    end_seconds = parse_time_seconds(options.end) if options.end else 0
    yt_dlp = os.environ.get("YTPDF_YTDLP_PATH")
    convert_url(
        options.url,
        options.output_directory,
        start_seconds=start_seconds,
        end_seconds=end_seconds,
        roi=RoiConfig.parse(options.roi),
        background=Background(options.background),
        motion=Motion(options.motion),
        yt_dlp_path=Path(yt_dlp) if yt_dlp else None,
        logger=lambda message: print(message, flush=True),
    )
    return 0

