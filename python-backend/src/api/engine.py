from __future__ import annotations

import sys
from dataclasses import dataclass
from importlib.util import find_spec
from typing import Protocol

from .schemas import ConversionRequest, EngineStatus, StemSeparationRequest
from .settings import Settings


class ConversionEngine(Protocol):
    def status(self) -> EngineStatus: ...

    def command(self, request: ConversionRequest | StemSeparationRequest) -> list[str]: ...

    def environment(self) -> dict[str, str]: ...


@dataclass(frozen=True, slots=True)
class PythonEngine:
    settings: Settings

    def status(self) -> EngineStatus:
        opencv_ready = find_spec("cv2") is not None
        message = (
            "Python OpenCV 변환 엔진을 사용할 수 있습니다."
            if opencv_ready
            else "Python OpenCV 의존성을 찾을 수 없습니다."
        )
        return EngineStatus(
            ready=opencv_ready,
            message=message,
            kind="python",
            engine_path=sys.executable,
        )

    def command(self, request: ConversionRequest | StemSeparationRequest) -> list[str]:
        command = (
            [sys.executable]
            if getattr(sys, "frozen", False)
            else [sys.executable, "-m", "api"]
        )
        if isinstance(request, StemSeparationRequest):
            command.extend(
                [
                    "stem-worker",
                    "--input-path",
                    str(request.resolved_input_path),
                    "--output-directory",
                    str(request.resolved_output_directory),
                    "--model",
                    request.model,
                ]
            )
            return command

        command.append("worker")
        command.extend(
            [
                "--output-directory",
                str(request.resolved_output_directory),
                "--roi",
                request.roi,
                "--background",
                request.background,
                "--motion",
                request.motion,
            ]
        )
        if request.start:
            command.extend(["--start", request.start])
        if request.end:
            command.extend(["--end", request.end])
        command.append(str(request.url))
        return command

    def environment(self) -> dict[str, str]:
        if self.settings.yt_dlp_path is None:
            return {}
        return {"YTPDF_YTDLP_PATH": str(self.settings.yt_dlp_path)}
