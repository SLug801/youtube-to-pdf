from pathlib import Path

import pytest
from pydantic import ValidationError

from ytpdf_api.schemas import ConversionRequest, parse_time_seconds


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("15", 15),
        ("01:15", 75),
        ("1:02:03", 3723),
        ("01:02.5", 62.5),
    ],
)
def test_parse_time_seconds(value: str, expected: float) -> None:
    assert parse_time_seconds(value) == expected


def test_conversion_request_rejects_inverted_range(tmp_path: Path) -> None:
    with pytest.raises(ValidationError, match="종료 시각"):
        ConversionRequest(
            url="https://www.youtube.com/watch?v=test",
            outputDirectory=tmp_path,
            start="02:00",
            end="01:59",
        )


def test_conversion_request_resolves_output_directory(tmp_path: Path) -> None:
    request = ConversionRequest(
        url="https://www.youtube.com/watch?v=test",
        outputDirectory=tmp_path,
    )

    assert request.resolved_output_directory == tmp_path.resolve()

