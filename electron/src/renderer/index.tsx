import React, { FormEvent, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import type {
  BackendEvent,
  BackendStatus,
  ConversionResult,
  RoiPreview,
} from '../shared/contracts';
import './styles.css';

interface RoiBounds {
  top: number;
  bottom: number;
  left: number;
  right: number;
}

type RoiKey = keyof RoiBounds;

const INITIAL_ROI: RoiBounds = {
  top: 0.7,
  bottom: 1,
  left: 0,
  right: 1,
};

function serializeRoi(bounds: RoiBounds): string {
  return [bounds.top, bounds.bottom, bounds.left, bounds.right]
    .map((value) => value.toFixed(2))
    .join(',');
}

function App(): React.JSX.Element {
  const [status, setStatus] = useState<BackendStatus | null>(null);
  const [url, setUrl] = useState('');
  const [outputDirectory, setOutputDirectory] = useState('');
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [roi, setRoi] = useState<RoiBounds>(INITIAL_ROI);
  const [preview, setPreview] = useState<RoiPreview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [background, setBackground] =
    useState<'translucent' | 'opaque'>('translucent');
  const [motion, setMotion] = useState<'scroll' | 'cut'>('scroll');
  const [logs, setLogs] = useState<string[]>([]);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<ConversionResult | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    window.youtubeToPdf.getBackendStatus().then(setStatus).catch((reason: Error) => {
      setError(reason.message);
    });

    return window.youtubeToPdf.onBackendEvent((event: BackendEvent) => {
      if (event.type === 'log') {
        setLogs((current) => [...current.slice(-499), event.message]);
      } else {
        setLogs((current) => [...current.slice(-499), event.message]);
      }
    });
  }, []);

  useEffect(() => {
    setPreview(null);
  }, [url, outputDirectory, start]);

  const selectOutputDirectory = async (): Promise<void> => {
    const selected = await window.youtubeToPdf.selectOutputDirectory();
    if (selected) {
      setOutputDirectory(selected);
    }
  };

  const startConversion = async (event: FormEvent): Promise<void> => {
    event.preventDefault();
    setError('');
    setResult(null);
    setLogs([]);
    setRunning(true);

    try {
      const conversionResult = await window.youtubeToPdf.startConversion({
        url: url.trim(),
        outputDirectory,
        start: start.trim() || undefined,
        end: end.trim() || undefined,
        roi: serializeRoi(roi),
        background,
        motion,
      });
      setResult(conversionResult);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setRunning(false);
    }
  };

  const loadPreview = async (): Promise<void> => {
    setError('');
    setPreviewLoading(true);
    try {
      const nextPreview = await window.youtubeToPdf.loadPreview({
        url: url.trim(),
        outputDirectory,
        at: start.trim() || undefined,
      });
      setPreview(nextPreview);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setPreviewLoading(false);
    }
  };

  const updateRoi = (key: RoiKey, rawValue: string): void => {
    const value = Number(rawValue);
    if (!Number.isFinite(value)) {
      return;
    }
    setRoi((current) => {
      const next = { ...current };
      if (key === 'top') {
        next.top = Math.min(Math.max(value, 0), current.bottom - 0.01);
      } else if (key === 'bottom') {
        next.bottom = Math.max(Math.min(value, 1), current.top + 0.01);
      } else if (key === 'left') {
        next.left = Math.min(Math.max(value, 0), current.right - 0.01);
      } else {
        next.right = Math.max(Math.min(value, 1), current.left + 0.01);
      }
      next[key] = Number(next[key].toFixed(2));
      return next;
    });
  };

  const cancelConversion = async (): Promise<void> => {
    const cancelled = await window.youtubeToPdf.cancelConversion();
    if (!cancelled) {
      setError('현재 실행 중인 작업이 없습니다.');
    }
  };

  return (
    <main className="shell">
      <header className="hero">
        <div>
          <p className="eyebrow">LOCAL DESKTOP CONVERTER</p>
          <h1>YouTube 악보를 PDF로</h1>
          <p className="subtitle">
            FastAPI 작업 계층과 Python OpenCV Worker가 안전하게 변환합니다.
          </p>
        </div>
        <span className={`status ${status?.ready ? 'ready' : 'waiting'}`}>
          <span className="status-dot" />
          {status?.ready ? '백엔드 준비됨' : '백엔드 확인 중'}
        </span>
      </header>

      <section className="panel">
        <form onSubmit={startConversion}>
          <label>
            YouTube URL
            <input
              type="url"
              value={url}
              onChange={(event) => setUrl(event.target.value)}
              placeholder="https://www.youtube.com/watch?v=..."
              required
              disabled={running}
            />
          </label>

          <label>
            출력 폴더
            <div className="path-row">
              <input
                value={outputDirectory}
                placeholder="PDF를 저장할 폴더를 선택하세요"
                readOnly
                required
              />
              <button type="button" className="secondary" onClick={selectOutputDirectory}>
                폴더 선택
              </button>
            </div>
          </label>

          <div className="time-grid">
            <label>
              시작 시각
              <input
                value={start}
                onChange={(event) => setStart(event.target.value)}
                placeholder="00:15"
                disabled={running}
              />
            </label>
            <label>
              종료 시각
              <input
                value={end}
                onChange={(event) => setEnd(event.target.value)}
                placeholder="04:45"
                disabled={running}
              />
            </label>
          </div>

          <div className="mode-grid">
            <label>
              배경
              <select
                value={background}
                onChange={(event) =>
                  setBackground(event.target.value as 'translucent' | 'opaque')
                }
                disabled={running}
              >
                <option value="translucent">반투명</option>
                <option value="opaque">불투명</option>
              </select>
            </label>
            <label>
              진행
              <select
                value={motion}
                onChange={(event) => setMotion(event.target.value as 'scroll' | 'cut')}
                disabled={running}
              >
                <option value="scroll">스크롤</option>
                <option value="cut">화면 전환</option>
              </select>
            </label>
          </div>

          <section className="roi-editor" aria-label="악보 영역 설정">
            <div className="roi-heading">
              <div>
                <h2>악보 영역 (ROI)</h2>
                <p>{serializeRoi(roi)}</p>
              </div>
              <button
                type="button"
                className="secondary"
                onClick={loadPreview}
                disabled={
                  running
                  || previewLoading
                  || !status?.ready
                  || !url.trim()
                  || !outputDirectory
                }
              >
                {previewLoading ? '프리뷰 준비 중…' : '프리뷰 불러오기'}
              </button>
            </div>

            {preview ? (
              <figure className="preview-frame">
                <img
                  src={preview.dataUrl}
                  alt={`${preview.timestampSeconds.toFixed(2)}초 영상 프리뷰`}
                  draggable={false}
                />
                <div className="roi-dim roi-dim-top" style={{ height: `${roi.top * 100}%` }} />
                <div
                  className="roi-dim roi-dim-bottom"
                  style={{ top: `${roi.bottom * 100}%` }}
                />
                <div
                  className="roi-dim roi-dim-left"
                  style={{
                    top: `${roi.top * 100}%`,
                    width: `${roi.left * 100}%`,
                    height: `${(roi.bottom - roi.top) * 100}%`,
                  }}
                />
                <div
                  className="roi-dim roi-dim-right"
                  style={{
                    top: `${roi.top * 100}%`,
                    left: `${roi.right * 100}%`,
                    height: `${(roi.bottom - roi.top) * 100}%`,
                  }}
                />
                <div
                  className="roi-selection"
                  style={{
                    top: `${roi.top * 100}%`,
                    left: `${roi.left * 100}%`,
                    width: `${(roi.right - roi.left) * 100}%`,
                    height: `${(roi.bottom - roi.top) * 100}%`,
                  }}
                />
                <figcaption>
                  {preview.timestampSeconds.toFixed(2)}초 · {preview.width}×{preview.height}
                </figcaption>
              </figure>
            ) : (
              <div className="preview-placeholder">
                URL과 출력 폴더를 입력한 뒤 프리뷰를 불러오세요.
              </div>
            )}

            <div className="roi-controls">
              {([
                ['top', '상단', 0, roi.bottom - 0.01],
                ['bottom', '하단', roi.top + 0.01, 1],
                ['left', '좌측', 0, roi.right - 0.01],
                ['right', '우측', roi.left + 0.01, 1],
              ] as const).map(([key, label, minimum, maximum]) => (
                <label key={key}>
                  <span>
                    {label}
                    <output>{roi[key].toFixed(2)}</output>
                  </span>
                  <input
                    type="range"
                    min={minimum}
                    max={maximum}
                    step="0.01"
                    value={roi[key]}
                    onChange={(event) => updateRoi(key, event.target.value)}
                    disabled={running}
                  />
                </label>
              ))}
            </div>
          </section>

          <div className="actions">
            <button
              type="submit"
              className="primary"
              disabled={running || previewLoading || !status?.ready || !outputDirectory}
            >
              {running ? '변환 중…' : 'PDF 변환 시작'}
            </button>
            <button
              type="button"
              className="danger"
              disabled={!running}
              onClick={cancelConversion}
            >
              취소
            </button>
          </div>
        </form>
      </section>

      {status && !status.ready && (
        <p className="notice">
          {status.message} <code>cd electron && npm run build:backend</code>
        </p>
      )}
      {error && <p className="notice error">{error}</p>}
      {result?.success && (
        <p className="notice success">완료: {result.outputPath}</p>
      )}

      <section className="panel log-panel">
        <div className="panel-title">
          <h2>처리 로그</h2>
          <span>{logs.length} lines</span>
        </div>
        <pre aria-live="polite">
          {logs.length > 0 ? logs.join('\n') : '변환을 시작하면 백엔드 로그가 여기에 표시됩니다.'}
        </pre>
      </section>

      <footer>
        Python OpenCV 엔진이 기본이며, YTPDF_ENGINE=java로 기존 엔진을 선택할 수 있습니다.
      </footer>
    </main>
  );
}

const root = document.getElementById('root');
if (!root) {
  throw new Error('Renderer root element를 찾을 수 없습니다.');
}

createRoot(root).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
