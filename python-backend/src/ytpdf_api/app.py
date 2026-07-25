from __future__ import annotations

import secrets
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Request, status
from fastapi.responses import FileResponse, StreamingResponse

from ytpdf_api.engine import ConversionEngine, JavaEngine, PythonEngine
from ytpdf_api.jobs import EngineUnavailableError, JobConflictError, JobManager
from ytpdf_api.schemas import (
    CancelResponse,
    ConversionRequest,
    HealthResponse,
    JobResponse,
)
from ytpdf_api.settings import Settings

VERSION = "0.1.0"


def create_engine(settings: Settings) -> ConversionEngine:
    if settings.engine_mode == "java":
        return JavaEngine(settings)
    if settings.engine_mode == "python":
        return PythonEngine(settings)
    if settings.engine_mode == "auto":
        python_engine = PythonEngine(settings)
        return python_engine if python_engine.status().ready else JavaEngine(settings)
    raise ValueError("YTPDF_ENGINE은 python, java 또는 auto여야 합니다.")


def get_settings(request: Request) -> Settings:
    return request.app.state.settings  # type: ignore[no-any-return]


def get_manager(request: Request) -> JobManager:
    return request.app.state.manager  # type: ignore[no-any-return]


def require_token(
    settings: Annotated[Settings, Depends(get_settings)],
    provided_token: Annotated[str | None, Header(alias="X-YTPDF-Token")] = None,
) -> None:
    if settings.api_token and (
        provided_token is None or not secrets.compare_digest(settings.api_token, provided_token)
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="API 토큰이 필요합니다.",
        )


def create_app(
    settings: Settings | None = None,
    manager: JobManager | None = None,
) -> FastAPI:
    resolved_settings = settings or Settings.from_env()
    resolved_manager = manager or JobManager(create_engine(resolved_settings))

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.settings = resolved_settings
        app.state.manager = resolved_manager
        yield
        await resolved_manager.shutdown()

    app = FastAPI(
        title="YouTube to PDF API",
        version=VERSION,
        description="Electron과 AI Agent를 위한 로컬 변환 작업 API",
        lifespan=lifespan,
    )
    router = APIRouter(dependencies=[Depends(require_token)])

    @router.get("/health", response_model=HealthResponse)
    async def health(job_manager: Annotated[JobManager, Depends(get_manager)]) -> HealthResponse:
        return HealthResponse(status="ok", version=VERSION, engine=job_manager.engine.status())

    @router.post(
        "/api/v1/jobs",
        response_model=JobResponse,
        status_code=status.HTTP_202_ACCEPTED,
    )
    async def create_job(
        body: ConversionRequest,
        job_manager: Annotated[JobManager, Depends(get_manager)],
    ) -> JobResponse:
        try:
            return await job_manager.submit(body)
        except EngineUnavailableError as error:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail=str(error),
            ) from error
        except JobConflictError as error:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(error)) from error

    @router.get("/api/v1/jobs/{job_id}", response_model=JobResponse)
    async def get_job(
        job_id: UUID,
        job_manager: Annotated[JobManager, Depends(get_manager)],
    ) -> JobResponse:
        job = job_manager.get(job_id)
        if job is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="작업이 없습니다.")
        return job

    @router.post("/api/v1/jobs/{job_id}/cancel", response_model=CancelResponse)
    async def cancel_job(
        job_id: UUID,
        job_manager: Annotated[JobManager, Depends(get_manager)],
    ) -> CancelResponse:
        result = await job_manager.cancel(job_id)
        if result is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="작업이 없습니다.")
        accepted, job = result
        return CancelResponse(accepted=accepted, job=job)

    @router.get("/api/v1/jobs/{job_id}/events")
    async def stream_events(
        job_id: UUID,
        job_manager: Annotated[JobManager, Depends(get_manager)],
        after: int = 0,
    ) -> StreamingResponse:
        if job_manager.get(job_id) is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="작업이 없습니다.")

        async def generate() -> AsyncIterator[str]:
            async for event in job_manager.iter_events(job_id, after):
                yield f"event: {event.type}\ndata: {event.model_dump_json(by_alias=True)}\n\n"

        return StreamingResponse(
            generate(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    @router.get("/api/v1/jobs/{job_id}/result", response_class=FileResponse)
    async def get_result(
        job_id: UUID,
        job_manager: Annotated[JobManager, Depends(get_manager)],
    ) -> FileResponse:
        job = job_manager.get(job_id)
        if job is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="작업이 없습니다.")
        if job.status.value != "succeeded":
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="아직 다운로드할 수 있는 결과가 없습니다.",
            )
        output_file = job_manager.output_file(job_id)
        if output_file is None or not output_file.is_file():
            raise HTTPException(
                status_code=status.HTTP_410_GONE,
                detail="결과 파일을 찾을 수 없습니다.",
            )
        return FileResponse(output_file, media_type="application/pdf", filename=output_file.name)

    app.include_router(router)
    return app


app = create_app()
