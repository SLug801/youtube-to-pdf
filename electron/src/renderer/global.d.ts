import type { ElectronApi } from '../shared/contracts';

declare global {
  interface Window {
    youtubeToPdf: ElectronApi;
  }
}

export {};
