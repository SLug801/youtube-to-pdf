from __future__ import annotations

import sys
import time
from pathlib import Path

import cv2
import numpy as np
from fastapi.testclient import TestClient

from ytpdf_api.app import create_app
from ytpdf_api.engine import PythonEngine
from ytpdf_api.jobs import JobManager
from ytpdf_api.schemas import ConversionRequest, EngineStatus
from ytpdf_api.settings import Settings


class FakeEngine:
    def status(self) -> EngineStatus:
        return EngineStatus(
            ready=True,
            jarPath="/tmp/fake.jar",
            javaCommand=sys.executable,
            message="테스트 엔진 준비됨",
        )

    def command(self, request: ConversionRequest) -> list[str]:
        output_path = request.resolved_output_directory / "sheet_01" / "sheet_01.pdf"
        script = (
            "from pathlib import Path;"
            f"p=Path({str(output_path)!r});"
            "p.parent.mkdir(parents=True,exist_ok=True);"
            "print('[테스트] 변환 중',flush=True);"
            "p.write_bytes(b'%PDF-1.4 test')"
        )
        return [sys.executable, "-c", script]

    def environment(self) -> dict[str, str]:
        return {}


def wait_for_terminal(client: TestClient, job_id: str) -> dict[str, object]:
    for _ in range(500):
        response = client.get(f"/api/v1/jobs/{job_id}", headers={"X-YTPDF-Token": "test"})
        body: dict[str, object] = response.json()
        if body["status"] in {"succeeded", "failed", "cancelled"}:
            return body
        time.sleep(0.02)
    raise AssertionError("테스트 작업이 제한 시간 안에 끝나지 않았습니다.")


def test_health_requires_token(tmp_path: Path) -> None:
    settings = Settings(api_token="test", jar_path=tmp_path / "missing.jar")
    app = create_app(settings, JobManager(FakeEngine()))

    with TestClient(app) as client:
        unauthorized = client.get("/health")
        authorized = client.get("/health", headers={"X-YTPDF-Token": "test"})

    assert unauthorized.status_code == 401
    assert authorized.status_code == 200
    assert authorized.json()["engine"]["ready"] is True


def test_job_runs_in_worker_process_and_exposes_result(tmp_path: Path) -> None:
    settings = Settings(api_token="test", jar_path=tmp_path / "fake.jar")
    app = create_app(settings, JobManager(FakeEngine()))
    headers = {"X-YTPDF-Token": "test"}

    with TestClient(app) as client:
        created = client.post(
            "/api/v1/jobs",
            headers=headers,
            json={
                "url": "https://www.youtube.com/watch?v=test",
                "outputDirectory": str(tmp_path),
                "start": "00:15",
            },
        )
        assert created.status_code == 202
        job_id = created.json()["id"]

        terminal = wait_for_terminal(client, job_id)
        events = client.get(f"/api/v1/jobs/{job_id}/events", headers=headers)
        result = client.get(f"/api/v1/jobs/{job_id}/result", headers=headers)

    assert terminal["status"] == "succeeded"
    assert terminal["outputPath"] == str(tmp_path / "sheet_01" / "sheet_01.pdf")
    assert "event: started" in events.text
    assert "event: log" in events.text
    assert "event: finished" in events.text
    assert "[테스트] 변환 중" in events.text
    assert result.status_code == 200
    assert result.content.startswith(b"%PDF")


def test_rejects_second_active_job(tmp_path: Path) -> None:
    class SlowEngine(FakeEngine):
        def command(self, request: ConversionRequest) -> list[str]:
            return [sys.executable, "-c", "import time; time.sleep(10)"]

    settings = Settings(api_token="test", jar_path=tmp_path / "fake.jar")
    app = create_app(settings, JobManager(SlowEngine()))
    headers = {"X-YTPDF-Token": "test"}
    payload = {
        "url": "https://www.youtube.com/watch?v=test",
        "outputDirectory": str(tmp_path),
    }

    with TestClient(app) as client:
        first = client.post("/api/v1/jobs", headers=headers, json=payload)
        second = client.post("/api/v1/jobs", headers=headers, json=payload)
        cancelled = client.post(
            f"/api/v1/jobs/{first.json()['id']}/cancel",
            headers=headers,
        )

    assert first.status_code == 202
    assert second.status_code == 409
    assert cancelled.status_code == 200
    assert cancelled.json()["accepted"] is True


def test_api_runs_real_python_worker_to_pdf(tmp_path: Path) -> None:
    work_directory = tmp_path / "sheet_01"
    work_directory.mkdir()
    video = work_directory / "video.mp4"
    writer = cv2.VideoWriter(
        str(video),
        cv2.VideoWriter_fourcc(*"mp4v"),
        20,
        (240, 100),
    )
    assert writer.isOpened()
    for page in range(2):
        frame = np.full((100, 240, 3), 255, dtype=np.uint8)
        for baseline in (22, 60):
            for delta in (0, 6, 12):
                cv2.line(frame, (0, baseline + delta), (239, baseline + delta), (0, 0, 0), 1)
        for index, x in enumerate(range(18, 230, 43)):
            cv2.line(
                frame,
                (x, 12 + page * 9),
                (x, 48 + page * 9),
                (0, 0, 0),
                2,
            )
            cv2.putText(
                frame,
                str(index + page * 5),
                (x + 4, 45 + page * 8),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.4,
                (0, 0, 0),
                1,
            )
        for _ in range(20):
            writer.write(frame)
    writer.release()

    settings = Settings(api_token="test", engine_mode="python")
    app = create_app(settings, JobManager(PythonEngine(settings)))
    headers = {"X-YTPDF-Token": "test"}
    with TestClient(app) as client:
        created = client.post(
            "/api/v1/jobs",
            headers=headers,
            json={
                "url": "https://www.youtube.com/watch?v=test",
                "outputDirectory": str(tmp_path),
                "roi": "0,1,0,1",
                "background": "opaque",
                "motion": "cut",
            },
        )
        assert created.status_code == 202
        terminal = wait_for_terminal(client, created.json()["id"])

    assert terminal["status"] == "succeeded", terminal["message"]
    output_path = Path(str(terminal["outputPath"]))
    assert output_path.read_bytes().startswith(b"%PDF")
