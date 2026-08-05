import React, { FormEvent, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import type {
  BackendEvent,
  BackendStatus,
  ConversionResult,
  RoiPreview,
} from '../shared/contracts';
import './styles.css';
import { TempoPage } from './tempo';

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
  const [activeTool, setActiveTool] = useState<'converter' | 'tempo'>('tempo');
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
  const [logsOpen, setLogsOpen] = useState(false);

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

  const activityMessage = error
    || (result?.success ? `완료 · ${result.outputPath}` : '')
    || (running ? logs[logs.length - 1] || '변환 작업을 준비하고 있습니다.' : '')
    || status?.message
    || '백엔드 상태를 확인하고 있습니다.';
  const activityKind = error
    ? 'error'
    : result?.success
      ? 'success'
      : running
        ? 'running'
        : 'idle';

  return (
    <main className={`app-shell ${activeTool === 'tempo' ? 'tempo-shell' : ''}`}>
      <header className="app-header">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">
            <span />
            <span />
            <span />
          </span>
          <div>
            <h1>Score Lab</h1>
            <p>악보 추출과 리듬 연습</p>
          </div>
        </div>
        <nav className="tool-navigation" aria-label="도구 전환">
          <button
            type="button"
            className={activeTool === 'converter' ? 'active' : ''}
            onClick={() => setActiveTool('converter')}
          >
            악보 변환
          </button>
          <button
            type="button"
            className={activeTool === 'tempo' ? 'active' : ''}
            onClick={() => setActiveTool('tempo')}
          >
            Tempo
          </button>
        </nav>
        {activeTool === 'converter' ? (
          <div className={`backend-status ${status?.ready ? 'ready' : 'waiting'}`}>
            <span className="status-dot" />
            {status?.ready ? '백엔드 준비됨' : '백엔드 확인 중'}
          </div>
        ) : (
          <div className="tempo-header-label"><span />DRUM PRACTICE</div>
        )}
      </header>

      {activeTool === 'converter' ? (
        <>
      <form className="workbench" onSubmit={startConversion}>
        <aside className="settings-pane">
          <div className="pane-heading">
            <div>
              <h2>변환 설정</h2>
              <p>영상과 악보 유형을 지정하세요.</p>
            </div>
          </div>

          <div className="settings-content">
            <section className="setting-section">
              <h3>입력</h3>
              <label className="field">
                <span>YouTube URL</span>
                <input
                  type="url"
                  value={url}
                  onChange={(event) => setUrl(event.target.value)}
                  placeholder="youtube.com/watch?v=..."
                  required
                  disabled={running}
                />
              </label>

              <label className="field">
                <span>출력 폴더</span>
                <div className="path-row">
                  <input
                    value={outputDirectory}
                    placeholder="저장 위치 선택"
                    title={outputDirectory}
                    readOnly
                    required
                  />
                  <button
                    type="button"
                    className="icon-button folder-button"
                    onClick={selectOutputDirectory}
                    aria-label="출력 폴더 선택"
                    title="출력 폴더 선택"
                  >
                    <svg viewBox="0 0 20 20" aria-hidden="true">
                      <path d="M2.5 5.5h5l1.7 2h8.3v7.8a1.7 1.7 0 0 1-1.7 1.7H4.2a1.7 1.7 0 0 1-1.7-1.7V5.5Z" />
                      <path d="M2.5 7.5V4.7A1.7 1.7 0 0 1 4.2 3h3.1l1.6 2h6.9a1.7 1.7 0 0 1 1.7 1.7v.8" />
                    </svg>
                  </button>
                </div>
              </label>
            </section>

            <section className="setting-section">
              <h3>변환 구간</h3>
              <div className="time-grid">
                <label className="field">
                  <span>시작</span>
                  <input
                    value={start}
                    onChange={(event) => setStart(event.target.value)}
                    placeholder="00:00"
                    disabled={running}
                  />
                </label>
                <label className="field">
                  <span>종료</span>
                  <input
                    value={end}
                    onChange={(event) => setEnd(event.target.value)}
                    placeholder="영상 끝"
                    disabled={running}
                  />
                </label>
              </div>
            </section>

            <section className="setting-section mode-section">
              <div className="section-label">
                <h3>배경 처리</h3>
                <span>악보 뒤 영상 노출 여부</span>
              </div>
              <div className="choice-grid">
                <button
                  type="button"
                  className={`choice-button ${background === 'translucent' ? 'selected' : ''}`}
                  aria-pressed={background === 'translucent'}
                  onClick={() => setBackground('translucent')}
                  disabled={running}
                >
                  <strong>반투명</strong>
                  <small>영상 위 악보</small>
                </button>
                <button
                  type="button"
                  className={`choice-button ${background === 'opaque' ? 'selected' : ''}`}
                  aria-pressed={background === 'opaque'}
                  onClick={() => setBackground('opaque')}
                  disabled={running}
                >
                  <strong>불투명</strong>
                  <small>단색 배경 악보</small>
                </button>
              </div>

              <div className="section-label motion-label">
                <h3>진행 방식</h3>
                <span>악보가 바뀌는 형태</span>
              </div>
              <div className="choice-grid">
                <button
                  type="button"
                  className={`choice-button ${motion === 'scroll' ? 'selected' : ''}`}
                  aria-pressed={motion === 'scroll'}
                  onClick={() => setMotion('scroll')}
                  disabled={running}
                >
                  <strong>스크롤</strong>
                  <small>좌우로 연속 이동</small>
                </button>
                <button
                  type="button"
                  className={`choice-button ${motion === 'cut' ? 'selected' : ''}`}
                  aria-pressed={motion === 'cut'}
                  onClick={() => setMotion('cut')}
                  disabled={running}
                >
                  <strong>화면 전환</strong>
                  <small>페이지 단위 교체</small>
                </button>
              </div>
            </section>
          </div>

          <div className="settings-actions">
            <button
              type="submit"
              className="primary-button"
              disabled={
                running
                || previewLoading
                || !status?.ready
                || !url.trim()
                || !outputDirectory
              }
            >
              {running ? (
                <>
                  <span className="spinner" aria-hidden="true" />
                  변환 중
                </>
              ) : 'PDF 변환 시작'}
            </button>
            <button
              type="button"
              className="cancel-button"
              disabled={!running}
              onClick={cancelConversion}
            >
              취소
            </button>
          </div>
        </aside>

        <section className="preview-pane">
          <div className="preview-heading">
            <div>
              <div className="title-row">
                <h2>악보 영역</h2>
                <code>{serializeRoi(roi)}</code>
              </div>
              <p>파란 테두리 안쪽만 PDF 변환에 사용됩니다.</p>
            </div>
            <button
              type="button"
              className="preview-button"
              onClick={loadPreview}
              disabled={
                running
                || previewLoading
                || !status?.ready
                || !url.trim()
                || !outputDirectory
              }
            >
              <svg viewBox="0 0 20 20" aria-hidden="true">
                <path d="M16.4 6.2A7 7 0 1 0 17 11" />
                <path d="M13.4 3.5h3.4v3.4" />
              </svg>
              {previewLoading ? '불러오는 중' : preview ? '새로고침' : '프리뷰 불러오기'}
            </button>
          </div>

          <div className="preview-workspace">
            {preview ? (
              <figure
                className="preview-frame"
                style={{ aspectRatio: `${preview.width} / ${preview.height}` }}
              >
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
                <span className="viewfinder" aria-hidden="true">
                  <i />
                </span>
                <strong>프리뷰가 아직 없습니다</strong>
                <p>URL과 저장 위치를 입력한 뒤 프리뷰를 불러오세요.</p>
              </div>
            )}
          </div>

          <div className="roi-controls">
            {([
              ['top', '상단', 0, roi.bottom - 0.01],
              ['bottom', '하단', roi.top + 0.01, 1],
              ['left', '좌측', 0, roi.right - 0.01],
              ['right', '우측', roi.left + 0.01, 1],
            ] as const).map(([key, label, minimum, maximum]) => (
              <label key={key} className="roi-control">
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
      </form>

      <footer className={`activity-bar ${activityKind}`}>
        <div className="activity-message" title={activityMessage}>
          <span className="activity-indicator" />
          <span>{activityMessage}</span>
        </div>
        <button
          type="button"
          className={`log-toggle ${logsOpen ? 'active' : ''}`}
          onClick={() => setLogsOpen((current) => !current)}
          aria-expanded={logsOpen}
        >
          처리 로그
          <span>{logs.length}</span>
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <path d={logsOpen ? 'm4 10 4-4 4 4' : 'm4 6 4 4 4-4'} />
          </svg>
        </button>
      </footer>

      {logsOpen && (
        <section className="log-drawer" aria-label="처리 로그">
          <div className="log-heading">
            <div>
              <h2>처리 로그</h2>
              <span>{logs.length}줄</span>
            </div>
            <button
              type="button"
              className="close-log"
              onClick={() => setLogsOpen(false)}
              aria-label="처리 로그 닫기"
            >
              ×
            </button>
          </div>
          <pre aria-live="polite">
            {logs.length > 0
              ? logs.join('\n')
              : '변환을 시작하면 처리 과정이 여기에 표시됩니다.'}
          </pre>
        </section>
      )}
        </>
      ) : (
        <TempoPage />
      )}
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
