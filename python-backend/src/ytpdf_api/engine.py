from __future__ import annotations

import shutil
import sys
from dataclasses import dataclass
from importlib.util import find_spec
from pathlib import Path
from typing import Protocol

from ytpdf_api.schemas import ConversionRequest, EngineStatus
from ytpdf_api.settings import Settings


class ConversionEngine(Protocol):
    def status(self) -> EngineStatus: ...

    def command(self, request: ConversionRequest) -> list[str]: ...

    def environment(self) -> dict[str, str]: ...


@dataclass(frozen=True, slots=True)
class JavaEngine:
    settings: Settings

    def status(self) -> EngineStatus:
        jar_exists = self.settings.jar_path.is_file()
        java_exists = (
            Path(self.settings.java_command).is_file()
            if Path(self.settings.java_command).is_absolute()
            else shutil.which(self.settings.java_command) is not None
        )
        ready = jar_exists and java_exists
        if not jar_exists:
            message = "Java 백엔드 JAR가 없습니다. 먼저 Gradle 빌드를 실행하세요."
        elif not java_exists:
            message = "Java 21 실행 파일을 찾을 수 없습니다."
        else:
            message = "FastAPI에서 Java 변환 엔진을 사용할 수 있습니다."
        return EngineStatus(
            ready=ready,
            jar_path=str(self.settings.jar_path),
            java_command=self.settings.java_command,
            message=message,
            kind="java",
            engine_path=str(self.settings.jar_path),
        )

    def command(self, request: ConversionRequest) -> list[str]:
        args = [
            self.settings.java_command,
            "-Djna.nosys=true",
            "-Djna.protected=true",
            "-Dfile.encoding=UTF-8",
        ]
        if self.settings.yt_dlp_path is not None:
            args.append(f"-Dytpdf.ytdlp={self.settings.yt_dlp_path}")
        args.extend(["-jar", str(self.settings.jar_path)])
        if request.start:
            args.extend(["--start", request.start])
        if request.end:
            args.extend(["--end", request.end])
        args.append(str(request.url))
        return args

    def environment(self) -> dict[str, str]:
        return {}


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
            jar_path=str(self.settings.jar_path),
            java_command=self.settings.java_command,
            message=message,
            kind="python",
            engine_path=sys.executable,
        )

    def command(self, request: ConversionRequest) -> list[str]:
        command = (
            [sys.executable, "worker"]
            if getattr(sys, "frozen", False)
            else [sys.executable, "-m", "ytpdf_api", "worker"]
        )
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
