from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True, slots=True)
class Settings:
    host: str = "127.0.0.1"
    port: int = 8765
    api_token: str = ""
    yt_dlp_path: Path | None = None

    @classmethod
    def from_env(cls) -> Settings:
        yt_dlp = os.environ.get("YTPDF_YTDLP_PATH")
        return cls(
            host=os.environ.get("YTPDF_API_HOST", "127.0.0.1"),
            port=int(os.environ.get("YTPDF_API_PORT", "8765")),
            api_token=os.environ.get("YTPDF_API_TOKEN", ""),
            yt_dlp_path=Path(yt_dlp).resolve() if yt_dlp else None,
        )
