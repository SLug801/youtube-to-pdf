import sys
from pathlib import Path

import api.worker
import core.pipeline
from api.engine import PythonEngine
from api.schemas import ConversionRequest, StemSeparationRequest
from api.settings import Settings
from api.worker import run_cli, run_stem_worker


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
        "api",
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


def test_python_engine_builds_stem_worker_contract(tmp_path: Path) -> None:
    audio = tmp_path / "practice.wav"
    audio.touch()
    request = StemSeparationRequest(
        inputPath=audio,
        outputDirectory=tmp_path,
        model="htdemucs_6s",
    )

    assert PythonEngine(Settings()).command(request) == [
        sys.executable,
        "-m",
        "api",
        "stem-worker",
        "--input-path",
        str(audio),
        "--output-directory",
        str(tmp_path),
        "--model",
        "htdemucs_6s",
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
    monkeypatch.setattr(core.pipeline, "convert_url", fake_convert_url)

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


def test_stem_worker_runs_local_separator(tmp_path: Path, monkeypatch) -> None:
    audio = tmp_path / "practice.wav"
    audio.touch()
    captured: dict[str, object] = {}

    def fake_separate_stems(
        input_path: Path,
        output_directory: Path,
        **options: object,
    ) -> Path:
        captured.update(
            input_path=input_path,
            output_directory=output_directory,
            options=options,
        )
        return output_directory / "stem-lab" / "htdemucs" / input_path.stem

    monkeypatch.setattr(api.worker, "separate_stems", fake_separate_stems)

    assert run_stem_worker(
        [
            "--input-path",
            str(audio),
            "--output-directory",
            str(tmp_path),
            "--model",
            "htdemucs",
        ]
    ) == 0
    assert captured["input_path"] == audio
    assert captured["output_directory"] == tmp_path
