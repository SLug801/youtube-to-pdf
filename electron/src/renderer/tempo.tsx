import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  MetronomeEngine,
  type AccentLevel,
  type ClickSound,
  type MetronomeConfig,
  type TickEvent,
} from './metronome';
import './tempo.css';

const MIN_BPM = 20;
const MAX_BPM = 300;
const PRESET_STORAGE_KEY = 'ytpdf-tempo-presets-v1';

interface TempoPreset {
  id: string;
  name: string;
  bpm: number;
  beatsPerBar: number;
  denominator: number;
  subdivision: number;
  accents: AccentLevel[];
}

function clampBpm(value: number): number {
  return Math.min(MAX_BPM, Math.max(MIN_BPM, Math.round(value)));
}

function formatElapsed(seconds: number): string {
  const minutes = Math.floor(seconds / 60).toString().padStart(2, '0');
  const remainder = (seconds % 60).toString().padStart(2, '0');
  return `${minutes}:${remainder}`;
}

function readPresets(): TempoPreset[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(PRESET_STORAGE_KEY) ?? '[]');
    return Array.isArray(value) ? value as TempoPreset[] : [];
  } catch {
    return [];
  }
}

function PlayIcon({ playing }: { playing: boolean }): React.JSX.Element {
  return playing ? (
    <svg viewBox="0 0 28 28" aria-hidden="true"><path d="M7 6h5v16H7zM16 6h5v16h-5z" /></svg>
  ) : (
    <svg viewBox="0 0 28 28" aria-hidden="true"><path d="m9 5 14 9L9 23V5Z" /></svg>
  );
}

export function TempoPage(): React.JSX.Element {
  const [bpm, setBpm] = useState(100);
  const [playing, setPlaying] = useState(false);
  const [beatsPerBar, setBeatsPerBar] = useState(4);
  const [denominator, setDenominator] = useState(4);
  const [subdivision, setSubdivision] = useState(1);
  const [accents, setAccents] = useState<AccentLevel[]>([2, 1, 1, 1]);
  const [sound, setSound] = useState<ClickSound>('classic');
  const [volume, setVolume] = useState(0.8);
  const [activeBeat, setActiveBeat] = useState(0);
  const [activeSubdivision, setActiveSubdivision] = useState(0);
  const [barCount, setBarCount] = useState(0);
  const [elapsed, setElapsed] = useState(0);
  const [gapEnabled, setGapEnabled] = useState(false);
  const [audibleBars, setAudibleBars] = useState(2);
  const [mutedBars, setMutedBars] = useState(1);
  const [isGapBar, setIsGapBar] = useState(false);
  const [trainerEnabled, setTrainerEnabled] = useState(false);
  const [trainerStep, setTrainerStep] = useState(5);
  const [trainerBars, setTrainerBars] = useState(4);
  const [presetName, setPresetName] = useState('');
  const [presets, setPresets] = useState<TempoPreset[]>(readPresets);
  const tapTimes = useRef<number[]>([]);
  const startedAt = useRef(0);
  const configRef = useRef<MetronomeConfig>({
    bpm,
    beatsPerBar,
    subdivision,
    accents,
    volume,
    sound,
    gapEnabled,
    audibleBars,
    mutedBars,
  });
  const trainerConfigRef = useRef({
    enabled: trainerEnabled,
    step: trainerStep,
    bars: trainerBars,
  });
  const lastTrainerBar = useRef(0);

  useEffect(() => {
    configRef.current = {
      bpm,
      beatsPerBar,
      subdivision,
      accents,
      volume,
      sound,
      gapEnabled,
      audibleBars,
      mutedBars,
    };
  }, [bpm, beatsPerBar, subdivision, accents, volume, sound, gapEnabled, audibleBars, mutedBars]);

  useEffect(() => {
    trainerConfigRef.current = {
      enabled: trainerEnabled,
      step: trainerStep,
      bars: trainerBars,
    };
  }, [trainerBars, trainerEnabled, trainerStep]);

  const handleTick = useCallback((event: TickEvent): void => {
    setActiveBeat(event.beat);
    setActiveSubdivision(event.subdivision);
    setIsGapBar(!event.audible);
    if (event.beat === 0 && event.subdivision === 0) {
      const nextBar = event.bar + 1;
      setBarCount(nextBar);
      const trainer = trainerConfigRef.current;
      if (
        trainer.enabled
        && nextBar > 1
        && nextBar % trainer.bars === 1
        && lastTrainerBar.current !== nextBar
      ) {
        lastTrainerBar.current = nextBar;
        setBpm((current) => clampBpm(current + trainer.step));
      }
    }
  }, []);

  const engineRef = useRef<MetronomeEngine | null>(null);
  useEffect(() => {
    const engine = new MetronomeEngine(() => configRef.current, handleTick);
    engineRef.current = engine;
    return () => {
      engine.dispose();
      engineRef.current = null;
    };
  }, [handleTick]);

  useEffect(() => {
    if (!playing) {
      return undefined;
    }
    const timer = window.setInterval(() => {
      setElapsed(Math.floor((Date.now() - startedAt.current) / 1000));
    }, 250);
    return () => window.clearInterval(timer);
  }, [playing]);

  useEffect(() => {
    localStorage.setItem(PRESET_STORAGE_KEY, JSON.stringify(presets));
  }, [presets]);

  const stop = useCallback((): void => {
    engineRef.current?.stop();
    setPlaying(false);
    setActiveBeat(0);
    setActiveSubdivision(0);
    setIsGapBar(false);
  }, []);

  const start = useCallback(async (): Promise<void> => {
    setBarCount(0);
    setElapsed(0);
    lastTrainerBar.current = 0;
    startedAt.current = Date.now();
    await engineRef.current?.start();
    setPlaying(true);
  }, []);

  const togglePlayback = useCallback((): void => {
    if (playing) {
      stop();
    } else {
      void start();
    }
  }, [playing, start, stop]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent): void => {
      const target = event.target as HTMLElement | null;
      if (target?.matches('input, select, textarea, button, a, [contenteditable="true"]')) {
        return;
      }
      if (event.code === 'Space') {
        event.preventDefault();
        togglePlayback();
      } else if (event.key === 'ArrowUp') {
        event.preventDefault();
        setBpm((current) => clampBpm(current + (event.shiftKey ? 5 : 1)));
      } else if (event.key === 'ArrowDown') {
        event.preventDefault();
        setBpm((current) => clampBpm(current - (event.shiftKey ? 5 : 1)));
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [togglePlayback]);

  const changeBeatsPerBar = (value: number): void => {
    const next = Math.min(12, Math.max(1, value));
    setBeatsPerBar(next);
    setAccents((current) => Array.from(
      { length: next },
      (_, index) => current[index] ?? (index === 0 ? 2 : 1),
    ));
  };

  const tapTempo = (): void => {
    const now = performance.now();
    const previous = tapTimes.current.at(-1);
    if (previous === undefined || now - previous > 2000) {
      tapTimes.current = [now];
      return;
    }
    tapTimes.current = [...tapTimes.current.slice(-5), now];
    if (tapTimes.current.length >= 2) {
      const intervals = tapTimes.current.slice(1).map(
        (time, index) => time - tapTimes.current[index],
      );
      const average = intervals.reduce((sum, interval) => sum + interval, 0) / intervals.length;
      setBpm(clampBpm(60000 / average));
    }
  };

  const cycleAccent = (index: number): void => {
    setAccents((current) => current.map(
      (level, beat) => beat === index ? ((level + 1) % 3) as AccentLevel : level,
    ));
  };

  const savePreset = (): void => {
    const name = presetName.trim() || `${bpm} BPM · ${beatsPerBar}/${denominator}`;
    const preset: TempoPreset = {
      id: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
      name,
      bpm,
      beatsPerBar,
      denominator,
      subdivision,
      accents,
    };
    setPresets((current) => [preset, ...current].slice(0, 20));
    setPresetName('');
  };

  const loadPreset = (preset: TempoPreset): void => {
    setBpm(clampBpm(preset.bpm));
    setBeatsPerBar(preset.beatsPerBar);
    setDenominator(preset.denominator);
    setSubdivision(preset.subdivision);
    setAccents(preset.accents);
  };

  const tempoMarking = bpm < 60 ? 'Largo' : bpm < 76 ? 'Adagio' : bpm < 108
    ? 'Andante' : bpm < 120 ? 'Moderato' : bpm < 168 ? 'Allegro' : 'Presto';

  return (
    <>
      <section className={`tempo-page ${isGapBar ? 'gap-active' : ''}`}>
        <aside className="tempo-sidebar">
          <div className="tempo-side-heading">
            <p>연습 도구</p>
            <h2>Practice Lab</h2>
          </div>

          <section className="practice-card">
            <div className="practice-title">
              <div><span className="practice-number">01</span><h3>갭 트레이너</h3></div>
              <button
                type="button"
                className={`switch ${gapEnabled ? 'on' : ''}`}
                onClick={() => setGapEnabled((current) => !current)}
                aria-pressed={gapEnabled}
                aria-label="갭 트레이너 켜기"
              ><span /></button>
            </div>
            <p>클릭을 주기적으로 비워 내면의 박자를 점검합니다.</p>
            <div className="practice-fields">
              <label>클릭 마디<input type="number" min="1" max="16" value={audibleBars} onChange={(event) => setAudibleBars(Math.max(1, Number(event.target.value)))} /></label>
              <label>쉼 마디<input type="number" min="1" max="16" value={mutedBars} onChange={(event) => setMutedBars(Math.max(1, Number(event.target.value)))} /></label>
            </div>
          </section>

          <section className="practice-card">
            <div className="practice-title">
              <div><span className="practice-number">02</span><h3>스피드 트레이너</h3></div>
              <button
                type="button"
                className={`switch ${trainerEnabled ? 'on' : ''}`}
                onClick={() => setTrainerEnabled((current) => !current)}
                aria-pressed={trainerEnabled}
                aria-label="스피드 트레이너 켜기"
              ><span /></button>
            </div>
            <p>정해진 마디마다 템포를 자동으로 올립니다.</p>
            <div className="practice-fields">
              <label>증가 BPM<input type="number" min="1" max="20" value={trainerStep} onChange={(event) => setTrainerStep(Math.max(1, Number(event.target.value)))} /></label>
              <label>반복 마디<input type="number" min="1" max="32" value={trainerBars} onChange={(event) => setTrainerBars(Math.max(1, Number(event.target.value)))} /></label>
            </div>
          </section>

          <section className="preset-section">
            <div className="preset-heading"><h3>내 프리셋</h3><span>{presets.length}/20</span></div>
            <div className="preset-create">
              <input value={presetName} onChange={(event) => setPresetName(event.target.value)} placeholder="곡 또는 연습 이름" maxLength={40} />
              <button type="button" onClick={savePreset} aria-label="현재 설정 저장">+</button>
            </div>
            <div className="preset-list">
              {presets.length === 0 ? (
                <p className="preset-empty">자주 쓰는 템포를 저장해 보세요.</p>
              ) : presets.map((preset) => (
                <div className="preset-item" key={preset.id}>
                  <button type="button" onClick={() => loadPreset(preset)}>
                    <strong>{preset.name}</strong>
                    <span>{preset.bpm} BPM · {preset.beatsPerBar}/{preset.denominator}</span>
                  </button>
                  <button type="button" className="preset-delete" onClick={() => setPresets((current) => current.filter(({ id }) => id !== preset.id))} aria-label={`${preset.name} 삭제`}>×</button>
                </div>
              ))}
            </div>
          </section>
        </aside>

        <div className="tempo-stage">
          <div className="tempo-status-row">
            <span className={playing ? 'live' : ''}><i />{playing ? isGapBar ? 'GAP · 박자 유지' : 'PLAYING' : 'READY'}</span>
            <div><span>{formatElapsed(elapsed)}</span><span>{barCount} 마디</span></div>
          </div>

          <div className="beat-visual" aria-label={`${beatsPerBar}박 중 ${activeBeat + 1}박`}>
            {accents.map((level, index) => (
              <button
                type="button"
                key={index}
                className={`beat-dot level-${level} ${playing && activeBeat === index ? 'active' : ''}`}
                onClick={() => cycleAccent(index)}
                title="클릭하여 악센트 · 일반 · 음소거 전환"
              >
                <span>{index + 1}</span>
                <i />
              </button>
            ))}
          </div>

          <div className="bpm-control">
            <button type="button" onClick={() => setBpm((current) => clampBpm(current - 1))} aria-label="BPM 1 감소">−</button>
            <label>
              <input type="number" min={MIN_BPM} max={MAX_BPM} value={bpm} onChange={(event) => setBpm(clampBpm(Number(event.target.value)))} aria-label="분당 박자" />
              <span>BPM</span>
              <small>{tempoMarking}</small>
            </label>
            <button type="button" onClick={() => setBpm((current) => clampBpm(current + 1))} aria-label="BPM 1 증가">+</button>
          </div>

          <input className="bpm-slider" type="range" min={MIN_BPM} max={MAX_BPM} value={bpm} onChange={(event) => setBpm(Number(event.target.value))} aria-label="BPM 슬라이더" />

          <div className="transport-row">
            <button type="button" className="tap-button" onClick={tapTempo}><kbd>T</kbd><span>TAP</span></button>
            <button type="button" className={`play-button ${playing ? 'playing' : ''}`} onClick={togglePlayback} aria-label={playing ? '메트로놈 정지' : '메트로놈 재생'}><PlayIcon playing={playing} /></button>
            <div className="shortcut-note"><kbd>Space</kbd><span>재생 / 정지</span></div>
          </div>

          <div className="subdivision-pulse" aria-hidden="true">
            {Array.from({ length: subdivision }, (_, index) => <i key={index} className={playing && activeSubdivision === index ? 'active' : ''} />)}
          </div>
        </div>

        <aside className="tempo-controls">
          <section className="control-section">
            <div className="control-heading"><span>01</span><div><h3>박자표</h3><p>한 마디의 박자 구성</p></div></div>
            <div className="signature-control">
              <div><button type="button" onClick={() => changeBeatsPerBar(beatsPerBar + 1)}>⌃</button><strong>{beatsPerBar}</strong><button type="button" onClick={() => changeBeatsPerBar(beatsPerBar - 1)}>⌄</button></div>
              <i />
              <div><button type="button" onClick={() => setDenominator(denominator === 4 ? 8 : 4)}>⌃</button><strong>{denominator}</strong><button type="button" onClick={() => setDenominator(denominator === 4 ? 8 : 4)}>⌄</button></div>
            </div>
          </section>

          <section className="control-section">
            <div className="control-heading"><span>02</span><div><h3>세분음</h3><p>한 박을 나누는 단위</p></div></div>
            <div className="subdivision-grid">
              {([
                [1, '♩', '4분음표'],
                [2, '♫', '8분음표'],
                [3, '3', '셋잇단음표'],
                [4, '♬', '16분음표'],
              ] as const).map(([value, symbol, label]) => (
                <button type="button" key={value} className={subdivision === value ? 'selected' : ''} onClick={() => setSubdivision(value)}><strong>{symbol}</strong><span>{label}</span></button>
              ))}
            </div>
          </section>

          <section className="control-section sound-section">
            <div className="control-heading"><span>03</span><div><h3>사운드</h3><p>클릭 음색과 출력</p></div></div>
            <div className="sound-options">
              {([['classic', 'Classic'], ['wood', 'Wood'], ['electronic', 'Digital']] as const).map(([value, label]) => (
                <button type="button" key={value} className={sound === value ? 'selected' : ''} onClick={() => setSound(value)}>{label}</button>
              ))}
            </div>
            <label className="volume-control"><span>볼륨</span><input type="range" min="0" max="1" step="0.01" value={volume} onChange={(event) => setVolume(Number(event.target.value))} /><output>{Math.round(volume * 100)}</output></label>
          </section>

          <div className="accent-legend"><span><i className="accent" />악센트</span><span><i />일반</span><span><i className="muted" />음소거</span></div>
        </aside>
      </section>

      <footer className="tempo-footer">
        <span>박자를 눌러 악센트를 변경할 수 있습니다.</span>
        <div><kbd>↑↓</kbd> BPM 미세 조정 <kbd>Shift + ↑↓</kbd> 5 BPM 조정</div>
      </footer>
    </>
  );
}
