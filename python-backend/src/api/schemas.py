from __future__ import annotations

import re
from datetime import datetime
from enum import StrEnum
from pathlib import Path
from typing import Annotated, Literal
from uuid import UUID

from pydantic import (
    BaseModel,
    ConfigDict,
    DirectoryPath,
    Field,
    HttpUrl,
    field_validator,
    model_validator,
)

TIME_PATTERN = re.compile(r"^(?:\d+(?::[0-5]?\d){0,2})(?:\.\d+)?$")


def to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ApiModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        serialize_by_alias=True,
    )


class JobStatus(StrEnum):
    QUEUED = "queued"
    RUNNING = "running"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    CANCELLED = "cancelled"

    @property
    def terminal(self) -> bool:
        return self in {self.SUCCEEDED, self.FAILED, self.CANCELLED}


def parse_time_seconds(value: str) -> float:
    if not TIME_PATTERN.fullmatch(value):
        raise ValueError("시각은 초, mm:ss 또는 hh:mm:ss 형식이어야 합니다.")
    parts = [float(part) for part in value.split(":")]
    if len(parts) == 1:
        return parts[0]
    if len(parts) == 2:
        return parts[0] * 60 + parts[1]
    if len(parts) == 3:
        return parts[0] * 3600 + parts[1] * 60 + parts[2]
    raise ValueError("시각은 초, mm:ss 또는 hh:mm:ss 형식이어야 합니다.")


class ConversionRequest(ApiModel):
    url: HttpUrl
    output_directory: DirectoryPath
    start: Annotated[str | None, Field(max_length=20)] = None
    end: Annotated[str | None, Field(max_length=20)] = None
    roi: Annotated[str, Field(max_length=80)] = "0.70,1.00,0.00,1.00"
    background: Literal["translucent", "opaque"] = "translucent"
    motion: Literal["scroll", "cut"] = "scroll"

    @field_validator("start", "end")
    @classmethod
    def validate_time(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        parse_time_seconds(normalized)
        return normalized

    @field_validator("roi")
    @classmethod
    def validate_roi(cls, value: str) -> str:
        try:
            parts = [float(part.strip()) for part in value.split(",")]
        except ValueError as error:
            raise ValueError("ROI 값은 숫자여야 합니다.") from error
        if len(parts) != 4:
            raise ValueError("ROI 형식: top,bottom,left,right")
        top, bottom, left, right = parts
        if not 0 <= top < bottom <= 1 or not 0 <= left < right <= 1:
            raise ValueError("ROI 비율은 0~1 범위에서 시작값이 종료값보다 작아야 합니다.")
        return ",".join(str(part) for part in parts)

    @model_validator(mode="after")
    def validate_range(self) -> ConversionRequest:
        if self.start is not None and self.end is not None:
            if parse_time_seconds(self.end) <= parse_time_seconds(self.start):
                raise ValueError("종료 시각은 시작 시각보다 뒤여야 합니다.")
        return self

    @property
    def resolved_output_directory(self) -> Path:
        return Path(self.output_directory).resolve()


class PreviewRequest(ApiModel):
    url: HttpUrl
    output_directory: DirectoryPath
    at: Annotated[str | None, Field(max_length=20)] = None

    @field_validator("at")
    @classmethod
    def validate_at(cls, value: str | None) -> str | None:
        if value is None:
            return None
        normalized = value.strip()
        parse_time_seconds(normalized)
        return normalized

    @property
    def resolved_output_directory(self) -> Path:
        return Path(self.output_directory).resolve()

    @property
    def at_seconds(self) -> float:
        return parse_time_seconds(self.at) if self.at is not None else 5


class EngineStatus(ApiModel):
    ready: bool
    message: str
    kind: Literal["python"] = "python"
    engine_path: str | None = None


class HealthResponse(ApiModel):
    status: Literal["ok"]
    version: str
    engine: EngineStatus


class JobResponse(ApiModel):
    id: UUID
    status: JobStatus
    message: str
    created_at: datetime
    started_at: datetime | None = None
    finished_at: datetime | None = None
    exit_code: int | None = None
    output_path: str | None = None


class JobEvent(ApiModel):
    sequence: int
    type: Literal["started", "log", "finished"]
    message: str
    timestamp: datetime
    stream: Literal["stdout", "stderr"] | None = None
    pid: int | None = None
    exit_code: int | None = None
    cancelled: bool | None = None


class CancelResponse(ApiModel):
    accepted: bool
    job: JobResponse
