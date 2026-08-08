from __future__ import annotations

import sys

import uvicorn

# PyInstaller가 이 파일을 스크립트 진입점으로 직접 분석하므로 절대 import를 사용한다.
from api.app import create_app
from api.settings import Settings


def print_help() -> None:
    print(
        """YouTube 악보 PDF 변환기

사용법:
  ytpdf convert [옵션] <URL>   영상을 PDF로 변환
  ytpdf-api                    로컬 FastAPI 서버 실행
  ytpdf --help                 이 도움말 표시
"""
    )


def main() -> None:
    arguments = sys.argv[1:]
    if arguments and arguments[0] in {"worker", "convert"}:
        from api.worker import run_cli, run_worker

        command = arguments[0]
        runner = run_worker if command == "worker" else run_cli
        raise SystemExit(runner(arguments[1:]))
    if arguments and arguments[0] in {"-h", "--help", "help"}:
        print_help()
        return
    if arguments:
        raise SystemExit(f"알 수 없는 명령: {arguments[0]}\n'ytpdf --help'를 확인하세요.")

    settings = Settings.from_env()
    uvicorn.run(
        create_app(settings),
        host=settings.host,
        port=settings.port,
        log_level="info",
        access_log=False,
    )


if __name__ == "__main__":
    main()
