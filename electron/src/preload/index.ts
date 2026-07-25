import { contextBridge, ipcRenderer } from 'electron';
import type {
  BackendEvent,
  ConversionRequest,
  ElectronApi,
  PreviewRequest,
} from '../shared/contracts';

const api: ElectronApi = {
  getBackendStatus: () => ipcRenderer.invoke('backend:status'),
  selectOutputDirectory: () => ipcRenderer.invoke('output:select-directory'),
  loadPreview: (request: PreviewRequest) =>
    ipcRenderer.invoke('backend:preview', request),
  startConversion: (request: ConversionRequest) =>
    ipcRenderer.invoke('backend:start', request),
  cancelConversion: () => ipcRenderer.invoke('backend:cancel'),
  onBackendEvent: (listener: (event: BackendEvent) => void) => {
    const handler = (_event: Electron.IpcRendererEvent, value: BackendEvent): void => {
      listener(value);
    };
    ipcRenderer.on('backend:event', handler);
    return () => ipcRenderer.removeListener('backend:event', handler);
  },
};

contextBridge.exposeInMainWorld('youtubeToPdf', api);
