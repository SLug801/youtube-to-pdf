from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _default_jar_path() -> Path:
    repository_root = Path(__file__).resolve().parents[3]
    return repository_root / "backend" / "build" / "libs" / "youtube-to-pdf-1.0.0-shaded.jar"


@dataclass(frozen=True, slots=True)
class Settings:
    host: str = "127.0.0.1"
    port: int = 8765
    api_token: str = ""
    jar_path: Path = field(default_factory=_default_jar_path)
    java_command: str = "java"
    yt_dlp_path: Path | None = None
    engine_mode: str = "python"

    @classmethod
    def from_env(cls) -> Settings:
        yt_dlp = os.environ.get("YTPDF_YTDLP_PATH")
        return cls(
            host=os.environ.get("YTPDF_API_HOST", "127.0.0.1"),
            port=int(os.environ.get("YTPDF_API_PORT", "8765")),
            api_token=os.environ.get("YTPDF_API_TOKEN", ""),
            jar_path=Path(os.environ.get("YTPDF_JAR_PATH", _default_jar_path())).resolve(),
            java_command=os.environ.get("YTPDF_JAVA_COMMAND", "java"),
            yt_dlp_path=Path(yt_dlp).resolve() if yt_dlp else None,
            engine_mode=os.environ.get("YTPDF_ENGINE", "python").lower(),
        )
