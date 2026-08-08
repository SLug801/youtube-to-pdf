from __future__ import annotations

from collections.abc import Callable, Sequence
from pathlib import Path

from PIL import Image
from reportlab.lib.pagesizes import A4
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen.canvas import Canvas

Logger = Callable[[str], None]


def build_pdf(
    image_paths: Sequence[Path],
    output_path: Path,
    logger: Logger,
) -> None:
    if not image_paths:
        raise ValueError("저장된 이미지가 없어 PDF를 생성할 수 없습니다.")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    sorted_paths = sorted(image_paths)
    page_width, page_height = A4
    margin = 20.0
    available_width = page_width - 2 * margin
    current_y = page_height - margin
    pdf = Canvas(str(output_path), pagesize=A4)

    logger(f"[PDF 생성] {len(sorted_paths)}장 → {output_path}")
    for index, image_path in enumerate(sorted_paths, start=1):
        with Image.open(image_path) as image:
            image_width, image_height = image.size
        draw_height = image_height * (available_width / image_width)
        if current_y - draw_height < margin:
            pdf.showPage()
            current_y = page_height - margin
        pdf.drawImage(
            ImageReader(str(image_path)),
            margin,
            current_y - draw_height,
            width=available_width,
            height=draw_height,
            preserveAspectRatio=True,
        )
        current_y -= draw_height + 25
        if index % 5 == 0:
            logger(f"[PDF] {index / len(sorted_paths) * 100:.0f}% ({index}/{len(sorted_paths)})")
        if index % 20 == 0:
            logger(f"  처리 중... {index}/{len(sorted_paths)}")
    pdf.save()
    logger(f"[완료] PDF 저장됨: {output_path}")

