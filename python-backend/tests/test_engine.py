import sys
from pathlib import Path

import ytpdf_api.worker
from ytpdf_api.engine import PythonEngine
from ytpdf_api.schemas import ConversionRequest
from ytpdf_api.settings import Settings
from ytpdf_api.worker import run_cli


def test_python_engine_builds_worker_contract(tmp_path: Path) -> None:
    request = ConversionRequest(
        url="https://www.youtube.com/watch?v=test",
        outputDirectory=tmp_path,
        start="00:15",
    )
    engine = PythonEngine(Settings(yt_dlp_path=tmp_path / "yt-dlp"))

    assert engine.status().kind == "python"
    assert engine.command(request) == [
        sys.executable,
        "-m",
        "ytpdf_api",
        "worker",
        "--output-directory",
        str(tmp_path),
        "--roi",
        "0.70,1.00,0.00,1.00",
        "--background",
        "translucent",
        "--motion",
        "scroll",
        "--start",
        "00:15",
        "https://www.youtube.com/watch?v=test",
    ]


def test_python_cli_converts_with_current_directory_default(
    tmp_path: Path,
    monkeypatch,
) -> None:
    captured: dict[str, object] = {}

    def fake_convert_url(url: str, output_directory: Path, **options: object) -> Path:
        captured.update(url=url, output_directory=output_directory, options=options)
        return output_directory / "sheet_01" / "sheet_01.pdf"

    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(ytpdf_api.worker, "convert_url", fake_convert_url)

    assert run_cli(
        [
            "--start",
            "00:15",
            "--roi",
            "0.60,1.00,0.00,1.00",
            "--background",
            "opaque",
            "--motion",
            "cut",
            "https://www.youtube.com/watch?v=test",
        ]
    ) == 0
    assert captured["url"] == "https://www.youtube.com/watch?v=test"
    assert captured["output_directory"] == tmp_path
