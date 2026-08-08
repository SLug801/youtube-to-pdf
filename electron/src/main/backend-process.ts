import { app } from 'electron';
import {
  spawn,
  type ChildProcessWithoutNullStreams,
} from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import type {
  BackendEvent,
  BackendStatus,
  ConversionRequest,
  ConversionResult,
  PreviewRequest,
  RoiPreview,
} from '../shared/contracts';

const API_EXECUTABLE = process.platform === 'win32' ? 'ytpdf-api.exe' : 'ytpdf-api';
const API_HOST = '127.0.0.1';
const MAX_PREVIEW_BYTES = 12 * 1024 * 1024;

function isFile(candidate: string): boolean {
  return fs.statSync(candidate, { throwIfNoEntry: false })?.isFile() ?? false;
}

interface BackendPaths {
  root: string;
  ytDlp?: string;
  apiCommand: string;
  apiArgs: string[];
  apiWorkingDirectory: string;
}

interface ApiHealth {
  status: 'ok';
  version: string;
  engine: {
    ready: boolean;
    kind: 'python';
    enginePath: string | null;
    message: string;
  };
}

type ApiJobStatus = 'queued' | 'running' | 'succeeded' | 'failed' | 'cancelled';

interface ApiJob {
  id: string;
  status: ApiJobStatus;
  message: string;
  exitCode: number | null;
  outputPath: string | null;
}

interface ApiJobEvent {
  type: 'started' | 'log' | 'finished';
  message: string;
  stream: 'stdout' | 'stderr' | null;
  pid: number | null;
  exitCode: number | null;
  cancelled: boolean | null;
}

interface ApiCancelResponse {
  accepted: boolean;
  job: ApiJob;
}

export class BackendProcess {
  private apiChild: ChildProcessWithoutNullStreams | null = null;
  private apiBaseUrl: string | null = null;
  private apiToken: string | null = null;
  private apiStartPromise: Promise<void> | null = null;
  private activeJobId: string | null = null;
  private apiErrorOutput = '';

  async status(): Promise<BackendStatus> {
    try {
      await this.ensureApi();
      const health = await this.requestJson<ApiHealth>('/health');
      return {
        ready: health.engine.ready,
        message: health.engine.ready
          ? `FastAPI ${health.version} · Python Worker 준비됨`
          : health.engine.message,
      };
    } catch (reason) {
      return {
        ready: false,
        message: reason instanceof Error ? reason.message : String(reason),
      };
    }
  }

  isRunning(): boolean {
    return this.activeJobId !== null;
  }

  async preview(request: PreviewRequest): Promise<RoiPreview> {
    if (this.activeJobId) {
      throw new Error('변환 작업 중에는 프리뷰를 불러올 수 없습니다.');
    }
    await this.ensureApi();
    const response = await this.request('/api/v1/preview', {
      method: 'POST',
      body: JSON.stringify(request),
    });
    if (response.headers.get('content-type')?.split(';')[0] !== 'image/jpeg') {
      throw new Error('FastAPI가 올바른 JPEG 프리뷰를 반환하지 않았습니다.');
    }
    const content = Buffer.from(await response.arrayBuffer());
    if (content.byteLength === 0 || content.byteLength > MAX_PREVIEW_BYTES) {
      throw new Error('프리뷰 이미지 크기가 허용 범위를 벗어났습니다.');
    }
    const width = Number(response.headers.get('X-YTPDF-Width'));
    const height = Number(response.headers.get('X-YTPDF-Height'));
    const timestampSeconds = Number(response.headers.get('X-YTPDF-Timestamp'));
    if (
      !Number.isFinite(width)
      || width <= 0
      || !Number.isFinite(height)
      || height <= 0
      || !Number.isFinite(timestampSeconds)
    ) {
      throw new Error('프리뷰 이미지 메타데이터가 올바르지 않습니다.');
    }
    return {
      dataUrl: `data:image/jpeg;base64,${content.toString('base64')}`,
      width,
      height,
      timestampSeconds,
    };
  }

  async start(
    request: ConversionRequest,
    emit: (event: BackendEvent) => void,
  ): Promise<ConversionResult> {
    if (this.activeJobId) {
      throw new Error('이미 변환 작업이 실행 중입니다.');
    }

    await this.ensureApi();
    const created = await this.requestJson<ApiJob>('/api/v1/jobs', {
      method: 'POST',
      body: JSON.stringify(request),
    });
    this.activeJobId = created.id;

    try {
      await this.consumeEvents(created.id, emit);
      const job = await this.requestJson<ApiJob>(`/api/v1/jobs/${created.id}`);
      const success = job.status === 'succeeded';
      const cancelled = job.status === 'cancelled';
      return {
        success,
        cancelled,
        exitCode: job.exitCode,
        outputPath: job.outputPath ?? undefined,
        message: job.message,
      };
    } finally {
      this.activeJobId = null;
    }
  }

  async cancel(): Promise<boolean> {
    const jobId = this.activeJobId;
    if (!jobId || !this.apiBaseUrl) {
      return false;
    }
    const result = await this.requestJson<ApiCancelResponse>(
      `/api/v1/jobs/${jobId}/cancel`,
      { method: 'POST' },
    );
    return result.accepted;
  }

  async shutdown(): Promise<void> {
    try {
      await this.cancel();
    } catch {
      // API 프로세스 종료 시 Worker 정리 루틴이 다시 실행된다.
    }

    const child = this.apiChild;
    if (!child) {
      return;
    }
    this.apiChild = null;
    this.apiBaseUrl = null;
    this.apiToken = null;

    if (child.exitCode === null) {
      child.kill('SIGTERM');
      await new Promise<void>((resolve) => {
        const timeout = setTimeout(resolve, 3000);
        child.once('close', () => {
          clearTimeout(timeout);
          resolve();
        });
      });
    }
  }

  private async ensureApi(): Promise<void> {
    if (this.apiChild && this.apiBaseUrl && this.apiToken) {
      return;
    }
    if (this.apiStartPromise) {
      return this.apiStartPromise;
    }

    this.apiStartPromise = this.startApi();
    try {
      await this.apiStartPromise;
    } finally {
      this.apiStartPromise = null;
    }
  }

  private async startApi(): Promise<void> {
    const paths = this.resolvePaths();
    const port = await this.reservePort();
    const token = crypto.randomBytes(32).toString('hex');
    const baseUrl = `http://${API_HOST}:${port}`;
    this.apiErrorOutput = '';

    const child = spawn(paths.apiCommand, paths.apiArgs, {
      cwd: paths.apiWorkingDirectory,
      env: {
        ...process.env,
        PYTHONUNBUFFERED: '1',
        YTPDF_API_HOST: API_HOST,
        YTPDF_API_PORT: String(port),
        YTPDF_API_TOKEN: token,
        ...(paths.ytDlp ? { YTPDF_YTDLP_PATH: paths.ytDlp } : {}),
      },
      windowsHide: true,
      shell: false,
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    this.apiChild = child;
    this.apiBaseUrl = baseUrl;
    this.apiToken = token;

    const rememberOutput = (chunk: Buffer): void => {
      this.apiErrorOutput = `${this.apiErrorOutput}${chunk.toString('utf8')}`.slice(-4000);
    };
    child.stdout.on('data', rememberOutput);
    child.stderr.on('data', rememberOutput);
    child.once('error', (error) => {
      this.apiErrorOutput = error.message;
    });
    child.once('close', () => {
      if (this.apiChild === child) {
        this.apiChild = null;
        this.apiBaseUrl = null;
        this.apiToken = null;
      }
    });

    for (let attempt = 0; attempt < 300; attempt += 1) {
      if (child.exitCode !== null) {
        break;
      }
      try {
        await this.requestJson<ApiHealth>('/health');
        return;
      } catch {
        await new Promise((resolve) => setTimeout(resolve, 100));
      }
    }

    child.kill('SIGTERM');
    const detail = this.apiErrorOutput.trim();
    throw new Error(
      detail
        ? `FastAPI 백엔드를 시작할 수 없습니다: ${detail}`
        : 'FastAPI 백엔드가 제한 시간 안에 준비되지 않았습니다.',
    );
  }

  private async consumeEvents(
    jobId: string,
    emit: (event: BackendEvent) => void,
  ): Promise<void> {
    const response = await this.request(`/api/v1/jobs/${jobId}/events`);
    if (!response.body) {
      throw new Error('FastAPI 이벤트 스트림을 열 수 없습니다.');
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });

      let boundary = buffer.indexOf('\n\n');
      while (boundary >= 0) {
        const block = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        const data = block
          .split('\n')
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trim())
          .join('');
        if (data) {
          this.forwardApiEvent(JSON.parse(data) as ApiJobEvent, emit);
        }
        boundary = buffer.indexOf('\n\n');
      }

      if (done) {
        return;
      }
    }
  }

  private forwardApiEvent(
    event: ApiJobEvent,
    emit: (event: BackendEvent) => void,
  ): void {
    if (event.type === 'started') {
      emit({
        type: 'started',
        pid: event.pid,
        message: event.message,
      });
    } else if (event.type === 'log') {
      emit({
        type: 'log',
        stream: event.stream ?? 'stdout',
        message: event.message,
      });
    } else {
      emit({
        type: 'finished',
        exitCode: event.exitCode,
        cancelled: event.cancelled ?? false,
        message: event.message,
      });
    }
  }

  private async requestJson<T>(endpoint: string, init?: RequestInit): Promise<T> {
    const response = await this.request(endpoint, init);
    return response.json() as Promise<T>;
  }

  private async request(endpoint: string, init?: RequestInit): Promise<Response> {
    if (!this.apiBaseUrl || !this.apiToken) {
      throw new Error('FastAPI 백엔드가 아직 준비되지 않았습니다.');
    }

    const headers = new Headers(init?.headers);
    headers.set('X-YTPDF-Token', this.apiToken);
    if (init?.body) {
      headers.set('Content-Type', 'application/json');
    }

    const response = await fetch(`${this.apiBaseUrl}${endpoint}`, {
      ...init,
      headers,
    });
    if (!response.ok) {
      let message = `FastAPI 요청 실패 (${response.status})`;
      try {
        const body = await response.json() as { detail?: string };
        if (body.detail) {
          message = body.detail;
        }
      } catch {
        // JSON 오류 본문이 아니면 상태 코드 메시지를 유지한다.
      }
      throw new Error(message);
    }
    return response;
  }

  private reservePort(): Promise<number> {
    return new Promise((resolve, reject) => {
      const server = net.createServer();
      server.once('error', reject);
      server.listen(0, API_HOST, () => {
        const address = server.address();
        if (!address || typeof address === 'string') {
          server.close();
          reject(new Error('FastAPI 로컬 포트를 할당할 수 없습니다.'));
          return;
        }
        server.close((error) => {
          if (error) {
            reject(error);
          } else {
            resolve(address.port);
          }
        });
      });
    });
  }

  private resolvePaths(): BackendPaths {
    const root = app.isPackaged
      ? path.join(process.resourcesPath, 'backend')
      : path.resolve(app.getAppPath(), 'vendor');
    const pythonProject = path.resolve(app.getAppPath(), '..', 'python-backend');

    const ytDlpNames =
      process.platform === 'win32' ? ['yt-dlp.exe', 'yt-dlp'] : ['yt-dlp'];
    const ytDlp = ytDlpNames
      .flatMap((name) => [path.join(root, name), path.join(root, 'bin', name)])
      .find((candidate) => isFile(candidate));
    const packagedApi = path.join(root, 'api', API_EXECUTABLE);
    const virtualEnvApi = path.join(
      pythonProject,
      '.venv',
      process.platform === 'win32' ? 'Scripts' : 'bin',
      API_EXECUTABLE,
    );

    let apiCommand: string;
    let apiArgs: string[];
    let apiWorkingDirectory: string;
    if (app.isPackaged) {
      apiCommand = packagedApi;
      apiArgs = [];
      apiWorkingDirectory = root;
    } else if (isFile(virtualEnvApi)) {
      apiCommand = virtualEnvApi;
      apiArgs = [];
      apiWorkingDirectory = pythonProject;
    } else {
      apiCommand = 'uv';
      apiArgs = ['run', '--project', pythonProject, 'ytpdf-api'];
      apiWorkingDirectory = pythonProject;
    }

    return {
      root,
      ytDlp,
      apiCommand,
      apiArgs,
      apiWorkingDirectory,
    };
  }
}
