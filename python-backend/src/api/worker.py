from __future__ import annotations

import argparse
import os
from pathlib import Path

from core.stem_separator import separate_stems

from .schemas import parse_time_seconds


def _run_conversion(
    arguments: list[str],
    *,
    program_name: str,
    description: str,
    output_required: bool,
) -> int:
    from core.models import Background, Motion, RoiConfig
    from core.pipeline import convert_url

    parser = argparse.ArgumentParser(prog=program_name, description=description)
    parser.add_argument(
        "--output-directory",
        required=output_required,
        type=Path,
        default=Path.cwd(),
        help="결과를 저장할 상위 폴더 (기본: 현재 폴더)",
    )
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


def run_worker(arguments: list[str]) -> int:
    return _run_conversion(
        arguments,
        program_name="ytpdf-api worker",
        description="FastAPI가 실행하는 Python 영상 처리 Worker",
        output_required=True,
    )


def run_stem_worker(arguments: list[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="ytpdf-api stem-worker",
        description="FastAPI가 실행하는 로컬 AI 음원 분리 Worker",
    )
    parser.add_argument("--input-path", required=True, type=Path)
    parser.add_argument("--output-directory", required=True, type=Path)
    parser.add_argument("--model", choices=["htdemucs", "htdemucs_6s"], default="htdemucs")
    options = parser.parse_args(arguments)
    separate_stems(
        options.input_path.resolve(),
        options.output_directory.resolve(),
        model=options.model,
        logger=lambda message: print(message, flush=True),
    )
    return 0


def run_cli(arguments: list[str]) -> int:
    return _run_conversion(
        arguments,
        program_name="ytpdf convert",
        description="YouTube 악보 영상을 Python OpenCV로 분석해 PDF로 변환",
        output_required=False,
    )
