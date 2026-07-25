import { app } from 'electron';
import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import type {
  BackendEvent,
  BackendStatus,
  ConversionRequest,
  ConversionResult,
} from '../shared/contracts';

const BACKEND_JAR = 'youtube-to-pdf-1.0.0-shaded.jar';

function isFile(candidate: string): boolean {
  return fs.statSync(candidate, { throwIfNoEntry: false })?.isFile() ?? false;
}

interface BackendPaths {
  root: string;
  jar: string;
  java: string;
  ytDlp?: string;
}

export class BackendProcess {
  private child: ChildProcessWithoutNullStreams | null = null;
  private cancellationRequested = false;

  status(): BackendStatus {
    const paths = this.resolvePaths();
    const ready = isFile(paths.jar);

    return {
      ready,
      jarPath: paths.jar,
      javaCommand: paths.java,
      message: ready
        ? 'Java 백엔드 JAR를 찾았습니다.'
        : '백엔드 JAR가 없습니다. npm run build:backend를 실행하세요.',
    };
  }

  isRunning(): boolean {
    return this.child !== null;
  }

  async start(
    request: ConversionRequest,
    emit: (event: BackendEvent) => void,
  ): Promise<ConversionResult> {
    if (this.child) {
      throw new Error('이미 변환 작업이 실행 중입니다.');
    }

    const paths = this.resolvePaths();
    if (!isFile(paths.jar)) {
      throw new Error('Java 백엔드 JAR를 찾을 수 없습니다. 먼저 백엔드를 빌드하세요.');
    }
    if (!fs.existsSync(request.outputDirectory)) {
      throw new Error('선택한 출력 폴더가 존재하지 않습니다.');
    }

    const args = [
      '-Djna.nosys=true',
      '-Djna.protected=true',
      '-Dfile.encoding=UTF-8',
    ];

    if (paths.ytDlp) {
      args.push(`-Dytpdf.ytdlp=${paths.ytDlp}`);
    }

    args.push('-jar', paths.jar);
    if (request.start) {
      args.push('--start', request.start);
    }
    if (request.end) {
      args.push('--end', request.end);
    }
    args.push(request.url);

    this.cancellationRequested = false;

    return new Promise<ConversionResult>((resolve, reject) => {
      const child = spawn(paths.java, args, {
        cwd: request.outputDirectory,
        detached: process.platform !== 'win32',
        windowsHide: true,
        shell: false,
        stdio: ['pipe', 'pipe', 'pipe'],
      });
      this.child = child;

      emit({
        type: 'started',
        pid: child.pid ?? null,
        message: 'Java 백엔드 변환을 시작했습니다.',
      });

      this.forwardLines(child.stdout, 'stdout', emit);
      this.forwardLines(child.stderr, 'stderr', emit);

      child.once('error', (error) => {
        this.child = null;
        reject(new Error(`Java 백엔드를 실행할 수 없습니다: ${error.message}`));
      });

      child.once('close', (exitCode) => {
        const cancelled = this.cancellationRequested;
        this.child = null;
        this.cancellationRequested = false;

        const success = exitCode === 0 && !cancelled;
        const result: ConversionResult = {
          success,
          cancelled,
          exitCode,
          outputPath: success
            ? path.join(request.outputDirectory, 'sheet_01', 'sheet_01.pdf')
            : undefined,
          message: cancelled
            ? '변환을 취소했습니다.'
            : success
              ? '변환이 완료되었습니다.'
              : `백엔드가 종료 코드 ${exitCode ?? 'unknown'}로 종료되었습니다.`,
        };

        emit({
          type: 'finished',
          exitCode,
          cancelled,
          message: result.message,
        });
        resolve(result);
      });
    });
  }

  cancel(): boolean {
    if (!this.child) {
      return false;
    }

    this.cancellationRequested = true;
    const pid = this.child.pid;

    if (process.platform === 'win32' && pid) {
      spawn('taskkill', ['/pid', String(pid), '/T', '/F'], {
        windowsHide: true,
        shell: false,
        stdio: 'ignore',
      });
    } else if (pid) {
      try {
        process.kill(-pid, 'SIGTERM');
      } catch {
        this.child.kill('SIGTERM');
      }
    } else {
      this.child.kill('SIGTERM');
    }
    return true;
  }

  private forwardLines(
    stream: NodeJS.ReadableStream,
    channel: 'stdout' | 'stderr',
    emit: (event: BackendEvent) => void,
  ): void {
    const lines = readline.createInterface({ input: stream });
    lines.on('line', (message) => {
      emit({
        type: 'log',
        stream: channel,
        message,
      });
    });
  }

  private resolvePaths(): BackendPaths {
    const root = app.isPackaged
      ? path.join(process.resourcesPath, 'backend')
      : path.resolve(app.getAppPath(), '..', 'backend');

    const bundledJava = path.join(
      root,
      'runtime',
      'bin',
      process.platform === 'win32' ? 'java.exe' : 'java',
    );

    const ytDlpNames =
      process.platform === 'win32' ? ['yt-dlp.exe', 'yt-dlp'] : ['yt-dlp'];
    const ytDlp = ytDlpNames
      .flatMap((name) => [path.join(root, name), path.join(root, 'bin', name)])
      .find((candidate) => isFile(candidate));

    return {
      root,
      jar: path.join(root, BACKEND_JAR),
      java: isFile(bundledJava) ? bundledJava : 'java',
      ytDlp,
    };
  }
}
