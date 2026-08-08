from __future__ import annotations

import wave
from collections.abc import Callable
from importlib.util import find_spec
from pathlib import Path
from typing import TYPE_CHECKING, Literal

import numpy as np

if TYPE_CHECKING:
    import torch

StemModel = Literal["htdemucs", "htdemucs_6s"]


def is_stem_separator_available() -> bool:
    return find_spec("av") is not None and find_spec("demucs") is not None


def stem_result_directory(
    input_path: Path,
    output_directory: Path,
    model: StemModel,
) -> Path:
    return output_directory.resolve() / "stem-lab" / model / input_path.stem


def separate_stems(
    input_path: Path,
    output_directory: Path,
    *,
    model: StemModel,
    logger: Callable[[str], None],
) -> Path:
    if not input_path.is_file():
        raise FileNotFoundError(f"입력 파일을 찾을 수 없습니다: {input_path}")
    if not output_directory.is_dir():
        raise NotADirectoryError(f"출력 폴더를 찾을 수 없습니다: {output_directory}")
    if not is_stem_separator_available():
        raise RuntimeError(
            "AI 음원 분리 구성요소가 없습니다. "
            "python-backend에서 'uv sync --extra stem'을 먼저 실행해 주세요."
        )

    # 무거운 PyAV/Torch/Demucs 모듈은 실제 작업을 시작할 때만 로드한다.
    import torch
    from demucs.apply import apply_model
    from demucs.pretrained import get_model

    model_name = model
    expected = stem_result_directory(input_path, output_directory, model_name)
    logger(f"[분리] 입력 파일: {input_path.name}")
    logger(f"[분리] 모델: {model_name}")
    logger("[분리] 첫 실행이면 AI 모델을 내려받느라 시간이 더 걸릴 수 있습니다.")
    model_instance = get_model(model_name)
    model_instance.cpu()
    model_instance.eval()
    wav = _load_audio(
        input_path,
        samplerate=int(model_instance.samplerate),
        logger=logger,
    )

    reference = wav.mean(0)
    mean = reference.mean()
    standard_deviation = reference.std().clamp_min(1e-8)
    normalized = (wav - mean) / standard_deviation
    logger("[분리] AI 모델로 악기별 소리를 분석합니다.")
    with torch.inference_mode():
        sources = apply_model(
            model_instance,
            normalized[None],
            device="cpu",
            shifts=1,
            split=True,
            overlap=0.25,
            progress=True,
            num_workers=0,
        )[0]
    sources = sources * standard_deviation + mean

    expected.mkdir(parents=True, exist_ok=True)
    for source, name in zip(sources, model_instance.sources, strict=True):
        output_path = expected / f"{name}.wav"
        _write_wave(output_path, source, int(model_instance.samplerate))
        logger(f"[저장] {output_path.name}")

    if not expected.is_dir() or not any(expected.glob("*.wav")):
        raise RuntimeError("분리 작업은 끝났지만 출력 WAV 파일을 찾을 수 없습니다.")
    logger(f"[완료] 분리 결과: {expected}")
    return expected


def _load_audio(
    input_path: Path,
    *,
    samplerate: int,
    logger: Callable[[str], None],
) -> torch.Tensor:
    import av
    import torch

    logger("[분리] 음원 트랙을 디코딩합니다.")
    chunks: list[np.ndarray] = []
    with av.open(str(input_path)) as container:
        if not container.streams.audio:
            raise ValueError("선택한 파일에 오디오 트랙이 없습니다.")
        stream = container.streams.audio[0]
        resampler = av.AudioResampler(format="fltp", layout="stereo", rate=samplerate)
        for frame in container.decode(stream):
            for converted in resampler.resample(frame):
                chunks.append(converted.to_ndarray().astype(np.float32, copy=False))
        for converted in resampler.resample(None):
            chunks.append(converted.to_ndarray().astype(np.float32, copy=False))

    if not chunks:
        raise ValueError("선택한 파일에서 오디오 샘플을 읽을 수 없습니다.")
    audio = np.concatenate(chunks, axis=1)
    return torch.from_numpy(audio)


def _write_wave(path: Path, source: torch.Tensor, samplerate: int) -> None:
    samples = source.detach().cpu().clamp(-1, 1)
    peak = float(samples.abs().max())
    if peak > 0.99:
        samples = samples * (0.99 / peak)
    interleaved = (
        samples.transpose(0, 1).contiguous().numpy() * np.iinfo(np.int16).max
    ).astype("<i2")
    with wave.open(str(path), "wb") as output:
        output.setnchannels(int(samples.shape[0]))
        output.setsampwidth(2)
        output.setframerate(samplerate)
        output.writeframes(interleaved.tobytes())
