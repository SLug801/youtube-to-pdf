import {
  app,
  BrowserWindow,
  dialog,
  ipcMain,
  type IpcMainInvokeEvent,
} from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import { BackendProcess } from './backend-process';
import type {
  BackendEvent,
  ConversionRequest,
  PreviewRequest,
} from '../shared/contracts';

declare const MAIN_WINDOW_WEBPACK_ENTRY: string;
declare const MAIN_WINDOW_PRELOAD_WEBPACK_ENTRY: string;

const backend = new BackendProcess();
let mainWindow: BrowserWindow | null = null;

function createWindow(): void {
  mainWindow = new BrowserWindow({
    width: 1120,
    height: 780,
    minWidth: 900,
    minHeight: 640,
    backgroundColor: '#0d1117',
    webPreferences: {
      preload: MAIN_WINDOW_PRELOAD_WEBPACK_ENTRY,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });

  mainWindow.loadURL(MAIN_WINDOW_WEBPACK_ENTRY);
  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  mainWindow.webContents.on('will-navigate', (event) => event.preventDefault());
  mainWindow.on('closed', () => {
    void backend.cancel();
    mainWindow = null;
  });
}

function requireTrustedSender(event: IpcMainInvokeEvent): void {
  if (!mainWindow || event.sender !== mainWindow.webContents) {
    throw new Error('허용되지 않은 IPC 요청입니다.');
  }
}

function validateUrl(value: unknown): string {
  if (typeof value !== 'string' || value.length > 2048) {
    throw new Error('URL을 확인해 주세요.');
  }
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error('올바른 URL을 입력해 주세요.');
  }
  if (!['https:', 'http:'].includes(url.protocol)) {
    throw new Error('HTTP 또는 HTTPS URL만 사용할 수 있습니다.');
  }
  return url.toString();
}

function validateOutputDirectory(value: unknown): string {
  if (
    typeof value !== 'string'
    || !fs.statSync(value, { throwIfNoEntry: false })?.isDirectory()
  ) {
    throw new Error('출력 폴더를 다시 선택해 주세요.');
  }
  return path.resolve(value);
}

function validateRequest(value: unknown): ConversionRequest {
  if (!value || typeof value !== 'object') {
    throw new Error('변환 요청 형식이 올바르지 않습니다.');
  }

  const request = value as Partial<ConversionRequest>;
  for (const [name, time] of [
    ['시작', request.start],
    ['종료', request.end],
  ] as const) {
    if (time !== undefined && (typeof time !== 'string' || time.length > 20)) {
      throw new Error(`${name} 시각 형식이 올바르지 않습니다.`);
    }
  }

  const roi = request.roi?.trim() || '0.70,1.00,0.00,1.00';
  if (roi.length > 80) {
    throw new Error('ROI 형식이 올바르지 않습니다.');
  }
  const background = request.background ?? 'translucent';
  if (!['translucent', 'opaque'].includes(background)) {
    throw new Error('지원하지 않는 배경 모드입니다.');
  }
  const motion = request.motion ?? 'scroll';
  if (!['scroll', 'cut'].includes(motion)) {
    throw new Error('지원하지 않는 진행 모드입니다.');
  }

  return {
    url: validateUrl(request.url),
    outputDirectory: validateOutputDirectory(request.outputDirectory),
    start: request.start?.trim() || undefined,
    end: request.end?.trim() || undefined,
    roi,
    background,
    motion,
  };
}

function validatePreviewRequest(value: unknown): PreviewRequest {
  if (!value || typeof value !== 'object') {
    throw new Error('프리뷰 요청 형식이 올바르지 않습니다.');
  }
  const request = value as Partial<PreviewRequest>;
  if (
    request.at !== undefined
    && (typeof request.at !== 'string' || request.at.length > 20)
  ) {
    throw new Error('프리뷰 시각 형식이 올바르지 않습니다.');
  }
  return {
    url: validateUrl(request.url),
    outputDirectory: validateOutputDirectory(request.outputDirectory),
    at: request.at?.trim() || undefined,
  };
}

function registerIpc(): void {
  ipcMain.handle('backend:status', (event) => {
    requireTrustedSender(event);
    return backend.status();
  });

  ipcMain.handle('output:select-directory', async (event) => {
    requireTrustedSender(event);
    const result = await dialog.showOpenDialog(mainWindow!, {
      title: 'PDF를 저장할 폴더 선택',
      defaultPath: app.getPath('documents'),
      properties: ['openDirectory', 'createDirectory'],
    });
    return result.canceled ? null : result.filePaths[0];
  });

  ipcMain.handle('backend:preview', (event, value: unknown) => {
    requireTrustedSender(event);
    return backend.preview(validatePreviewRequest(value));
  });

  ipcMain.handle('backend:start', async (event, value: unknown) => {
    requireTrustedSender(event);
    const request = validateRequest(value);
    const emit = (backendEvent: BackendEvent): void => {
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('backend:event', backendEvent);
      }
    };
    return backend.start(request, emit);
  });

  ipcMain.handle('backend:cancel', (event) => {
    requireTrustedSender(event);
    return backend.cancel();
  });
}

app.whenReady().then(() => {
  registerIpc();
  createWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

let shutdownStarted = false;
app.on('before-quit', (event) => {
  if (shutdownStarted) {
    return;
  }
  event.preventDefault();
  shutdownStarted = true;
  void backend.shutdown().finally(() => app.quit());
});
