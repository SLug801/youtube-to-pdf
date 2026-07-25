export interface BackendStatus {
  ready: boolean;
  jarPath: string;
  javaCommand: string;
  message: string;
}

export interface ConversionRequest {
  url: string;
  outputDirectory: string;
  start?: string;
  end?: string;
}

export type BackendEvent =
  | {
      type: 'started';
      pid: number | null;
      message: string;
    }
  | {
      type: 'log';
      stream: 'stdout' | 'stderr';
      message: string;
    }
  | {
      type: 'finished';
      exitCode: number | null;
      cancelled: boolean;
      message: string;
    };

export interface ConversionResult {
  success: boolean;
  cancelled: boolean;
  exitCode: number | null;
  outputPath?: string;
  message: string;
}

export interface ElectronApi {
  getBackendStatus(): Promise<BackendStatus>;
  selectOutputDirectory(): Promise<string | null>;
  startConversion(request: ConversionRequest): Promise<ConversionResult>;
  cancelConversion(): Promise<boolean>;
  onBackendEvent(listener: (event: BackendEvent) => void): () => void;
}
