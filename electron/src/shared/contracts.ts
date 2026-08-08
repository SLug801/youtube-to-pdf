export interface BackendStatus {
  ready: boolean;
  message: string;
}

export interface ConversionRequest {
  url: string;
  outputDirectory: string;
  start?: string;
  end?: string;
  roi?: string;
  background?: 'translucent' | 'opaque';
  motion?: 'scroll' | 'cut';
}

export interface PreviewRequest {
  url: string;
  outputDirectory: string;
  at?: string;
}

export interface RoiPreview {
  dataUrl: string;
  width: number;
  height: number;
  timestampSeconds: number;
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

export type StemModel = 'htdemucs' | 'htdemucs_6s';

export interface StemCapability {
  available: boolean;
  message: string;
  models: StemModel[];
}

export interface StemSeparationRequest {
  inputPath: string;
  outputDirectory: string;
  model: StemModel;
}

export interface StemSeparationResult extends ConversionResult {
  outputPath?: string;
}

export interface ElectronApi {
  getBackendStatus(): Promise<BackendStatus>;
  selectOutputDirectory(): Promise<string | null>;
  selectStemInputFile(): Promise<string | null>;
  selectStemOutputDirectory(): Promise<string | null>;
  getStemCapability(): Promise<StemCapability>;
  startStemSeparation(request: StemSeparationRequest): Promise<StemSeparationResult>;
  openLocalDirectory(directory: string): Promise<void>;
  loadPreview(request: PreviewRequest): Promise<RoiPreview>;
  startConversion(request: ConversionRequest): Promise<ConversionResult>;
  cancelConversion(): Promise<boolean>;
  onBackendEvent(listener: (event: BackendEvent) => void): () => void;
}
