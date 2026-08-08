import { contextBridge, ipcRenderer } from 'electron';
import type {
  BackendEvent,
  ConversionRequest,
  ElectronApi,
  PreviewRequest,
  StemSeparationRequest,
} from '../shared/contracts';

const api: ElectronApi = {
  getBackendStatus: () => ipcRenderer.invoke('backend:status'),
  selectOutputDirectory: () => ipcRenderer.invoke('output:select-directory'),
  selectStemInputFile: () => ipcRenderer.invoke('stems:select-input'),
  selectStemOutputDirectory: () => ipcRenderer.invoke('stems:select-output'),
  getStemCapability: () => ipcRenderer.invoke('stems:capability'),
  startStemSeparation: (request: StemSeparationRequest) =>
    ipcRenderer.invoke('stems:start', request),
  openLocalDirectory: (directory: string) =>
    ipcRenderer.invoke('local:open-directory', directory),
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
