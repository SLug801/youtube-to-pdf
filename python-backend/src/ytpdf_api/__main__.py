from __future__ import annotations

import sys

import uvicorn

from ytpdf_api.app import create_app
from ytpdf_api.settings import Settings


def main() -> None:
    if len(sys.argv) > 1 and sys.argv[1] == "worker":
        from ytpdf_api.worker import run_worker

        raise SystemExit(run_worker(sys.argv[2:]))
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
