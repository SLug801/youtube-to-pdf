from __future__ import annotations

import os
import shutil
import subprocess
from collections.abc import Callable
from pathlib import Path

Logger = Callable[[str], None]
VIDEO_SUFFIXES = {".mp4", ".mkv", ".webm"}


def resolve_yt_dlp(explicit_path: Path | None = None) -> str:
    if explicit_path is not None and explicit_path.is_file():
        return str(explicit_path)
    environment_path = os.environ.get("YTPDF_YTDLP_PATH")
    if environment_path and Path(environment_path).is_file():
        return str(Path(environment_path).resolve())
    discovered = shutil.which("yt-dlp")
    return discovered or "yt-dlp"


def download_video(
    url: str,
    output_directory: Path,
    logger: Logger,
    yt_dlp_path: Path | None = None,
) -> Path:
    output_directory.mkdir(parents=True, exist_ok=True)
    existing = next(
        (
            path
            for path in output_directory.iterdir()
            if path.is_file() and path.suffix.lower() in VIDEO_SUFFIXES
        ),
        None,
    )
    if existing is not None:
        logger(f"[확인] 기존 영상 파일 사용: {existing.name}")
        return existing

    logger(f"[다운로드] {url}")
    command = [
        resolve_yt_dlp(yt_dlp_path),
        "-f",
        "bestvideo[vcodec^=avc][height<=1080]/bestvideo[height<=1080]/22/18/best",
        "-o",
        str(output_directory / "video.%(ext)s"),
        "--no-playlist",
        url,
    ]
    try:
        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except OSError as error:
        raise OSError(
            "yt-dlp를 실행할 수 없습니다. PATH 또는 YTPDF_YTDLP_PATH를 확인하세요."
        ) from error

    assert process.stdout is not None
    for line in process.stdout:
        logger(line.rstrip())
    exit_code = process.wait()
    if exit_code != 0:
        raise OSError(f"yt-dlp 다운로드 실패 (exit code: {exit_code})")

    downloaded = next(
        (
            path
            for path in output_directory.iterdir()
            if path.is_file()
            and path.name.startswith("video")
            and path.suffix.lower() in VIDEO_SUFFIXES
        ),
        None,
    )
    if downloaded is None:
        raise OSError("다운로드된 영상 파일을 찾지 못했습니다.")
    return downloaded

