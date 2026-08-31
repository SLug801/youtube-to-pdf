from __future__ import annotations

import asyncio
import os
import signal
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Literal
from uuid import UUID, uuid4

from core.stem_separator import is_stem_separator_available, stem_result_directory

from .engine import ConversionEngine
from .schemas import (
    ConversionRequest,
    JobEvent,
    JobResponse,
    JobStatus,
    StemSeparationRequest,
)

JobRequest = ConversionRequest | StemSeparationRequest


class EngineUnavailableError(RuntimeError):
    pass


class JobConflictError(RuntimeError):
    pass


@dataclass(slots=True)
class JobRecord:
    id: UUID
    request: JobRequest
    status: JobStatus = JobStatus.QUEUED
    message: str = "작업이 대기 중입니다."
    created_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    started_at: datetime | None = None
    finished_at: datetime | None = None
    exit_code: int | None = None
    output_path: str | None = None
    cancellation_requested: bool = False
    process: asyncio.subprocess.Process | None = None
    events: list[JobEvent] = field(default_factory=list)
    event_condition: asyncio.Condition = field(default_factory=asyncio.Condition)

    def response(self) -> JobResponse:
        return JobResponse(
            id=self.id,
            kind="stems" if isinstance(self.request, StemSeparationRequest) else "score",
            status=self.status,
            message=self.message,
            created_at=self.created_at,
            started_at=self.started_at,
            finished_at=self.finished_at,
            exit_code=self.exit_code,
            output_path=self.output_path,
        )


class JobManager:
    def __init__(self, engine: ConversionEngine) -> None:
        self.engine = engine
        self._jobs: dict[UUID, JobRecord] = {}
        self._tasks: set[asyncio.Task[None]] = set()
        self._submit_lock = asyncio.Lock()

    @property
    def has_active_job(self) -> bool:
        return any(not job.status.terminal for job in self._jobs.values())

    async def submit(self, request: JobRequest) -> JobResponse:
        engine_status = self.engine.status()
        if not engine_status.ready:
            raise EngineUnavailableError(engine_status.message)
        if isinstance(request, StemSeparationRequest) and not is_stem_separator_available():
            raise EngineUnavailableError(
                "AI 음원 분리 구성요소가 없습니다. "
                "python-backend에서 'uv sync --extra stem'을 실행해 주세요."
            )

        async with self._submit_lock:
            if self.has_active_job:
                raise JobConflictError("현재 다른 작업이 실행 중입니다.")
            record = JobRecord(id=uuid4(), request=request)
            self._jobs[record.id] = record
            task = asyncio.create_task(self._run(record), name=f"ytpdf-job-{record.id}")
            self._tasks.add(task)
            task.add_done_callback(self._tasks.discard)
            return record.response()

    def get(self, job_id: UUID) -> JobResponse | None:
        record = self._jobs.get(job_id)
        return record.response() if record is not None else None

    def output_file(self, job_id: UUID) -> Path | None:
        record = self._jobs.get(job_id)
        if record is None or record.output_path is None:
            return None
        return Path(record.output_path)

    async def cancel(self, job_id: UUID) -> tuple[bool, JobResponse] | None:
        record = self._jobs.get(job_id)
        if record is None:
            return None
        if record.status.terminal:
            return False, record.response()

        record.cancellation_requested = True
        record.message = "작업 취소를 요청했습니다."
        await self._emit(record, "log", record.message, stream="stderr")
        if record.process is not None:
            await self._terminate(record.process)
        return True, record.response()

    async def iter_events(self, job_id: UUID, after: int = 0) -> AsyncIterator[JobEvent]:
        record = self._jobs.get(job_id)
        if record is None:
            return

        index = max(0, after)
        while True:
            async with record.event_condition:
                while index >= len(record.events) and not record.status.terminal:
                    await record.event_condition.wait()
                pending = record.events[index:]

            for event in pending:
                index += 1
                yield event

            if record.status.terminal and index >= len(record.events):
                return

    async def shutdown(self) -> None:
        active = [job for job in self._jobs.values() if not job.status.terminal]
        for job in active:
            job.cancellation_requested = True
            if job.process is not None:
                await self._terminate(job.process)
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)

    async def _run(self, record: JobRecord) -> None:
        if record.cancellation_requested:
            await self._finish_cancelled(record)
            return

        record.status = JobStatus.RUNNING
        record.started_at = datetime.now(UTC)
        engine_status = self.engine.status()
        task_name = (
            "음원 분리" if isinstance(record.request, StemSeparationRequest) else "악보 변환"
        )
        record.message = f"{engine_status.kind.capitalize()} {task_name} 엔진을 시작합니다."

        command = self.engine.command(record.request)
        environment = os.environ.copy()
        environment.update(self.engine.environment())
        process_options: dict[str, Any] = {}
        if os.name == "nt":
            process_options["creationflags"] = 0x00000200
        else:
            process_options["start_new_session"] = True

        try:
            process = await asyncio.create_subprocess_exec(
                *command,
                cwd=record.request.resolved_output_directory,
                env=environment,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                **process_options,
            )
            record.process = process
            await self._emit(
                record,
                "started",
                f"{engine_status.kind.capitalize()} Worker {task_name}을 시작했습니다.",
                pid=process.pid,
            )

            assert process.stdout is not None
            assert process.stderr is not None
            await asyncio.gather(
                self._forward_lines(record, process.stdout, "stdout"),
                self._forward_lines(record, process.stderr, "stderr"),
            )
            record.exit_code = await process.wait()

            if record.cancellation_requested:
                await self._finish_cancelled(record)
                return

            expected_output = self._expected_output(record.request)
            output_exists = (
                expected_output.is_dir()
                if isinstance(record.request, StemSeparationRequest)
                else expected_output.is_file()
            )
            if record.exit_code == 0 and output_exists:
                record.status = JobStatus.SUCCEEDED
                record.output_path = str(expected_output)
                record.message = f"{task_name}이 완료되었습니다."
            elif record.exit_code == 0:
                record.status = JobStatus.FAILED
                record.message = f"{task_name} 프로세스는 완료됐지만 결과를 찾을 수 없습니다."
            else:
                record.status = JobStatus.FAILED
                record.message = f"작업 엔진이 종료 코드 {record.exit_code}로 종료되었습니다."
            record.finished_at = datetime.now(UTC)
            await self._emit(
                record,
                "finished",
                record.message,
                exit_code=record.exit_code,
                cancelled=False,
            )
        except Exception as error:
            record.status = JobStatus.FAILED
            record.finished_at = datetime.now(UTC)
            record.message = f"작업 엔진을 실행할 수 없습니다: {error}"
            await self._emit(
                record,
                "finished",
                record.message,
                stream="stderr",
                exit_code=record.exit_code,
                cancelled=False,
            )
        finally:
            record.process = None

    @staticmethod
    def _expected_output(request: JobRequest) -> Path:
        if isinstance(request, StemSeparationRequest):
            return stem_result_directory(
                request.resolved_input_path,
                request.resolved_output_directory,
                request.model,
            )
        return request.resolved_output_directory / "sheet_01" / "sheet_01.pdf"

    async def _forward_lines(
        self,
        record: JobRecord,
        stream: asyncio.StreamReader,
        channel: Literal["stdout", "stderr"],
    ) -> None:
        while line := await stream.readline():
            message = line.decode("utf-8", errors="replace").rstrip()
            await self._emit(record, "log", message, stream=channel)

    async def _finish_cancelled(self, record: JobRecord) -> None:
        record.status = JobStatus.CANCELLED
        record.finished_at = datetime.now(UTC)
        record.message = "작업을 취소했습니다."
        await self._emit(
            record,
            "finished",
            record.message,
            exit_code=record.exit_code,
            cancelled=True,
        )

    async def _emit(
        self,
        record: JobRecord,
        event_type: Literal["started", "log", "finished"],
        message: str,
        *,
        stream: Literal["stdout", "stderr"] | None = None,
        pid: int | None = None,
        exit_code: int | None = None,
        cancelled: bool | None = None,
    ) -> None:
        event = JobEvent(
            sequence=len(record.events),
            type=event_type,
            message=message,
            timestamp=datetime.now(UTC),
            stream=stream,
            pid=pid,
            exit_code=exit_code,
            cancelled=cancelled,
        )
        async with record.event_condition:
            record.events.append(event)
            record.event_condition.notify_all()

    async def _terminate(self, process: asyncio.subprocess.Process) -> None:
        if process.returncode is not None:
            return
        if os.name == "nt":
            killer = await asyncio.create_subprocess_exec(
                "taskkill",
                "/pid",
                str(process.pid),
                "/T",
                "/F",
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.DEVNULL,
            )
            await killer.wait()
        else:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                return

        try:
            await asyncio.wait_for(process.wait(), timeout=3)
        except TimeoutError:
            if os.name == "nt":
                process.kill()
            else:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    return
            await process.wait()
