import sys
from pathlib import Path

from ytpdf_api.engine import JavaEngine, PythonEngine
from ytpdf_api.schemas import ConversionRequest
from ytpdf_api.settings import Settings


def test_java_engine_builds_existing_cli_contract(tmp_path: Path) -> None:
    jar = tmp_path / "backend.jar"
    yt_dlp = tmp_path / "yt-dlp"
    jar.touch()
    yt_dlp.touch()
    request = ConversionRequest(
        url="https://www.youtube.com/watch?v=test",
        outputDirectory=tmp_path,
        start="00:15",
        end="04:45",
    )
    engine = JavaEngine(
        Settings(
            jar_path=jar,
            java_command="/usr/bin/java",
            yt_dlp_path=yt_dlp,
        )
    )

    assert engine.command(request) == [
        "/usr/bin/java",
        "-Djna.nosys=true",
        "-Djna.protected=true",
        "-Dfile.encoding=UTF-8",
        f"-Dytpdf.ytdlp={yt_dlp}",
        "-jar",
        str(jar),
        "--start",
        "00:15",
        "--end",
        "04:45",
        "https://www.youtube.com/watch?v=test",
    ]


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
