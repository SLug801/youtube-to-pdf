import React, { useEffect, useMemo, useState } from 'react';
import type {
  BackendEvent,
  StemCapability,
  StemModel,
  StemSeparationResult,
} from '../shared/contracts';
import './stem-lab.css';

const FOUR_STEMS = [
  ['vocals', '보컬', 'VOICE'],
  ['drums', '드럼', 'RHYTHM'],
  ['bass', '베이스', 'LOW'],
  ['other', '나머지', 'OTHER'],
] as const;

const SIX_STEMS = [
  ...FOUR_STEMS.slice(0, 3),
  ['guitar', '기타', 'STRING'],
  ['piano', '피아노', 'KEYS'],
  ['other', '나머지', 'OTHER'],
] as const;

function fileName(path: string): string {
  return path.split(/[\\/]/).pop() ?? path;
}

export function StemLabPage(): React.JSX.Element {
  const [capability, setCapability] = useState<StemCapability | null>(null);
  const [inputPath, setInputPath] = useState('');
  const [outputDirectory, setOutputDirectory] = useState('');
  const [model, setModel] = useState<StemModel>('htdemucs');
  const [running, setRunning] = useState(false);
  const [logs, setLogs] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [result, setResult] = useState<StemSeparationResult | null>(null);

  useEffect(() => {
    window.youtubeToPdf
      .getStemCapability()
      .then(setCapability)
      .catch((reason: Error) => setError(reason.message));

    return window.youtubeToPdf.onBackendEvent((event: BackendEvent) => {
      setLogs((current) => [...current.slice(-199), event.message]);
    });
  }, []);

  const stems = useMemo(
    () => model === 'htdemucs_6s' ? SIX_STEMS : FOUR_STEMS,
    [model],
  );

  const selectInput = async (): Promise<void> => {
    const selected = await window.youtubeToPdf.selectStemInputFile();
    if (selected) {
      setInputPath(selected);
      setResult(null);
      setError('');
    }
  };

  const selectOutput = async (): Promise<void> => {
    const selected = await window.youtubeToPdf.selectStemOutputDirectory();
    if (selected) {
      setOutputDirectory(selected);
      setResult(null);
      setError('');
    }
  };

  const startSeparation = async (): Promise<void> => {
    setError('');
    setResult(null);
    setLogs([]);
    setRunning(true);
    try {
      const nextResult = await window.youtubeToPdf.startStemSeparation({
        inputPath,
        outputDirectory,
        model,
      });
      setResult(nextResult);
      if (!nextResult.success && !nextResult.cancelled) {
        setError(nextResult.message);
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    } finally {
      setRunning(false);
    }
  };

  const cancelSeparation = async (): Promise<void> => {
    const cancelled = await window.youtubeToPdf.cancelConversion();
    if (!cancelled) {
      setError('현재 실행 중인 작업이 없습니다.');
    }
  };

  const openResult = async (): Promise<void> => {
    if (!result?.outputPath) {
      return;
    }
    try {
      await window.youtubeToPdf.openLocalDirectory(result.outputPath);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : String(reason));
    }
  };

  const statusMessage = error
    || (result?.success ? '악기별 WAV 파일을 만들었습니다.' : '')
    || (running ? logs[logs.length - 1] || 'AI 모델을 준비하고 있습니다.' : '')
    || capability?.message
    || 'AI 음원 분리 엔진을 확인하고 있습니다.';

  return (
    <section className="stem-lab-page">
      <aside className="stem-setup-panel">
        <div className="stem-panel-heading">
          <span>01</span>
          <div>
            <p>LOCAL SOURCE</p>
            <h2>분리할 곡 선택</h2>
          </div>
        </div>

        <div className="stem-setup-content">
          <section className="stem-field-section">
            <label>입력 음원 또는 영상</label>
            <button
              type="button"
              className={`stem-path-button ${inputPath ? 'selected' : ''}`}
              onClick={selectInput}
              disabled={running}
            >
              <span className="stem-file-icon" aria-hidden="true" />
              <span>
                <strong>{inputPath ? fileName(inputPath) : '파일 선택'}</strong>
                <small>{inputPath || 'MP3, WAV, FLAC, M4A, MP4 등을 지원합니다.'}</small>
              </span>
            </button>
          </section>

          <section className="stem-field-section">
            <label>결과 저장 위치</label>
            <button
              type="button"
              className={`stem-path-button ${outputDirectory ? 'selected' : ''}`}
              onClick={selectOutput}
              disabled={running}
            >
              <span className="stem-folder-icon" aria-hidden="true" />
              <span>
                <strong>{outputDirectory ? fileName(outputDirectory) : '폴더 선택'}</strong>
                <small>{outputDirectory || '분리된 WAV 파일을 저장할 위치입니다.'}</small>
              </span>
            </button>
          </section>

          <section className="stem-field-section">
            <div className="stem-section-title">
              <label>분리 정밀도</label>
              <span>처리 시간과 결과 구성</span>
            </div>
            <div className="stem-model-options">
              <button
                type="button"
                className={model === 'htdemucs' ? 'active' : ''}
                onClick={() => setModel('htdemucs')}
                disabled={running}
              >
                <strong>4 STEM</strong>
                <small>안정적인 기본 분리</small>
              </button>
              <button
                type="button"
                className={model === 'htdemucs_6s' ? 'active' : ''}
                onClick={() => setModel('htdemucs_6s')}
                disabled={running}
              >
                <strong>6 STEM</strong>
                <small>기타·피아노 실험 분리</small>
              </button>
            </div>
          </section>

          {capability && !capability.available && (
            <div className="stem-engine-notice">
              <strong>AI 구성요소 설치 필요</strong>
              <p>개발 환경에서 아래 명령을 한 번 실행하면 분리 기능이 활성화됩니다.</p>
              <code>uv sync --extra stem</code>
            </div>
          )}
        </div>

        <div className="stem-actions">
          <button
            type="button"
            className="stem-primary-button"
            onClick={() => void startSeparation()}
            disabled={
              running
              || !capability?.available
              || !inputPath
              || !outputDirectory
            }
          >
            {running ? <><i /> 분리 중</> : '음원 분리 시작'}
          </button>
          <button
            type="button"
            className="stem-cancel-button"
            onClick={() => void cancelSeparation()}
            disabled={!running}
          >
            취소
          </button>
        </div>
      </aside>

      <section className="stem-stage">
        <header className="stem-stage-heading">
          <div>
            <span>02</span>
            <div>
              <p>STEM MAP</p>
              <h2>악기별 트랙</h2>
            </div>
          </div>
          <span className={`stem-engine-state ${capability?.available ? 'ready' : ''}`}>
            <i /> {capability?.available ? 'AI READY' : 'AI OFFLINE'}
          </span>
        </header>

        <div className="stem-stage-body">
          <div className="stem-wave-hero" aria-hidden="true">
            {Array.from({ length: 54 }, (_, index) => (
              <i key={index} style={{ height: `${18 + ((index * 29) % 70)}%` }} />
            ))}
          </div>

          <div className={`stem-track-grid ${model === 'htdemucs_6s' ? 'six' : ''}`}>
            {stems.map(([key, name, caption], index) => (
              <article key={key} className={running ? 'processing' : ''}>
                <div className="stem-track-index">{String(index + 1).padStart(2, '0')}</div>
                <div className={`stem-track-symbol ${key}`} aria-hidden="true">
                  <span />
                </div>
                <div>
                  <h3>{name}</h3>
                  <p>{caption}</p>
                </div>
                <span className="stem-track-format">WAV</span>
              </article>
            ))}
          </div>

          <div className={`stem-run-status ${error ? 'error' : result?.success ? 'success' : ''}`}>
            <span><i /> {statusMessage}</span>
            {result?.success && result.outputPath && (
              <button type="button" onClick={() => void openResult()}>
                결과 폴더 열기
              </button>
            )}
          </div>

          {(running || logs.length > 0) && (
            <div className="stem-log-preview">
              <span>PROCESS LOG</span>
              <p>{logs.slice(-3).join(' · ') || '작업을 준비하고 있습니다.'}</p>
            </div>
          )}
        </div>
      </section>
    </section>
  );
}
