import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  MetronomeEngine,
  type AccentLevel,
  type ClickSound,
  type MetronomeConfig,
  type TickEvent,
} from './metronome';
import './tempo.css';

const MIN_BPM = 10;
const MAX_BPM = 800;
const PRESET_STORAGE_KEY = 'ytpdf-tempo-presets-v2';

type TrainerUnit = 'bars' | 'seconds';
type DisplayMode = 'led' | 'pendulum';

interface TempoPreset {
  id: string;
  name: string;
  bpm: number;
  beatsPerBar: number;
  denominator: number;
  subdivision: number;
  accents: AccentLevel[];
  sound: ClickSound;
  volume: number;
  pan: number;
  gapEnabled: boolean;
  audibleBars: number;
  mutedBars: number;
  countInBars: number;
}

const SOUND_OPTIONS: ReadonlyArray<readonly [ClickSound, string]> = [
  ['classic', '클래식'],
  ['wood', '우드'],
  ['electronic', '디지털'],
  ['studio', '스튜디오'],
  ['live', '라이브 드럼'],
  ['rim', '림 클릭'],
  ['clave', '클라베'],
  ['cowbell', '카우벨'],
  ['hihat', '하이햇'],
  ['shaker', '셰이커'],
  ['beep', '비프'],
  ['pulse', '펄스'],
  ['soft', '소프트'],
  ['deep', '딥'],
];

const SUBDIVISION_OPTIONS = [
  [1, '♩', '4분'],
  [2, '♫', '8분'],
  [3, '3', '셋잇단'],
  [4, '♬', '16분'],
  [6, '6', '여섯잇단'],
  [8, '8', '32분'],
] as const;

const PITCHES = [
  ['C', 60], ['C♯', 61], ['D', 62], ['D♯', 63], ['E', 64], ['F', 65],
  ['F♯', 66], ['G', 67], ['G♯', 68], ['A', 69], ['A♯', 70], ['B', 71],
] as const;

function clampBpm(value: number): number {
  if (!Number.isFinite(value)) {
    return 100;
  }
  return Math.min(MAX_BPM, Math.max(MIN_BPM, Math.round(value)));
}

function clampInteger(value: number, minimum: number, maximum: number): number {
  if (!Number.isFinite(value)) {
    return minimum;
  }
  return Math.min(maximum, Math.max(minimum, Math.round(value)));
}

function formatElapsed(seconds: number): string {
  const minutes = Math.floor(seconds / 60).toString().padStart(2, '0');
  const remainder = (seconds % 60).toString().padStart(2, '0');
  return `${minutes}:${remainder}`;
}

function readPresets(): TempoPreset[] {
  try {
    const stored = localStorage.getItem(PRESET_STORAGE_KEY)
      ?? localStorage.getItem('ytpdf-tempo-presets-v1')
      ?? '[]';
    const value: unknown = JSON.parse(stored);
    if (!Array.isArray(value)) {
      return [];
    }
    return value.slice(0, 100).map((entry, index) => {
      const preset = entry as Partial<TempoPreset>;
      const beatsPerBar = clampInteger(preset.beatsPerBar ?? 4, 1, 13);
      return {
        id: typeof preset.id === 'string' ? preset.id : `legacy-${index}`,
        name: typeof preset.name === 'string' ? preset.name : `프리셋 ${index + 1}`,
        bpm: clampBpm(preset.bpm ?? 100),
        beatsPerBar,
        denominator: [2, 4, 8].includes(preset.denominator ?? 4)
          ? preset.denominator ?? 4
          : 4,
        subdivision: [1, 2, 3, 4, 6, 8].includes(preset.subdivision ?? 1)
          ? preset.subdivision ?? 1
          : 1,
        accents: Array.isArray(preset.accents)
          ? Array.from({ length: beatsPerBar }, (_, beat) => preset.accents?.[beat] ?? (beat === 0 ? 2 : 1))
          : Array.from({ length: beatsPerBar }, (_, beat) => beat === 0 ? 2 : 1),
        sound: SOUND_OPTIONS.some(([sound]) => sound === preset.sound) ? preset.sound ?? 'classic' : 'classic',
        volume: Math.min(1, Math.max(0, preset.volume ?? 0.8)),
        pan: Math.min(1, Math.max(-1, preset.pan ?? 0)),
        gapEnabled: preset.gapEnabled ?? false,
        audibleBars: clampInteger(preset.audibleBars ?? 2, 1, 16),
        mutedBars: clampInteger(preset.mutedBars ?? 1, 1, 16),
        countInBars: clampInteger(preset.countInBars ?? 0, 0, 2),
      };
    });
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
  const [pan, setPan] = useState(0);
  const [muted, setMuted] = useState(false);
  const [voiceCountEnabled, setVoiceCountEnabled] = useState(false);
  const [activeBeat, setActiveBeat] = useState(0);
  const [activeSubdivision, setActiveSubdivision] = useState(0);
  const [barCount, setBarCount] = useState(0);
  const [elapsed, setElapsed] = useState(0);
  const [countingIn, setCountingIn] = useState(false);
  const [countInBars, setCountInBars] = useState(0);
  const [gapEnabled, setGapEnabled] = useState(false);
  const [audibleBars, setAudibleBars] = useState(2);
  const [mutedBars, setMutedBars] = useState(1);
  const [isGapBar, setIsGapBar] = useState(false);
  const [trainerEnabled, setTrainerEnabled] = useState(false);
  const [trainerStep, setTrainerStep] = useState(5);
  const [trainerInterval, setTrainerInterval] = useState(4);
  const [trainerUnit, setTrainerUnit] = useState<TrainerUnit>('bars');
  const [trainerTarget, setTrainerTarget] = useState(160);
  const [autoStopBars, setAutoStopBars] = useState(0);
  const [autoStopSeconds, setAutoStopSeconds] = useState(0);
  const [displayMode, setDisplayMode] = useState<DisplayMode>('led');
  const [flashEnabled, setFlashEnabled] = useState(false);
  const [flashActive, setFlashActive] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);
  const [presetName, setPresetName] = useState('');
  const [presetQuery, setPresetQuery] = useState('');
  const [presets, setPresets] = useState<TempoPreset[]>(readPresets);
  const [selectedPresetId, setSelectedPresetId] = useState<string | null>(null);
  const [pitchCalibration, setPitchCalibration] = useState(440);
  const [leftPanelOpen, setLeftPanelOpen] = useState(true);
  const [rightPanelOpen, setRightPanelOpen] = useState(true);

  const tapTimes = useRef<number[]>([]);
  const startedAt = useRef(0);
  const lastTrainerBar = useRef(0);
  const lastTrainerSecond = useRef(0);
  const engineRef = useRef<MetronomeEngine | null>(null);
  const pitchContextRef = useRef<AudioContext | null>(null);
  const voiceCountRef = useRef(false);
  const importInputRef = useRef<HTMLInputElement | null>(null);
  const configRef = useRef<MetronomeConfig>({
    bpm, beatsPerBar, subdivision, accents, volume, sound, pan, muted,
    gapEnabled, audibleBars, mutedBars, countInBars,
  });
  const trainerConfigRef = useRef({
    enabled: trainerEnabled,
    step: trainerStep,
    interval: trainerInterval,
    unit: trainerUnit,
    target: trainerTarget,
  });
  const sessionConfigRef = useRef({ autoStopBars, autoStopSeconds, flashEnabled });

  useEffect(() => {
    configRef.current = {
      bpm, beatsPerBar, subdivision, accents, volume, sound, pan, muted,
      gapEnabled, audibleBars, mutedBars, countInBars,
    };
  }, [bpm, beatsPerBar, subdivision, accents, volume, sound, pan, muted, gapEnabled, audibleBars, mutedBars, countInBars]);

  useEffect(() => {
    trainerConfigRef.current = {
      enabled: trainerEnabled,
      step: trainerStep,
      interval: trainerInterval,
      unit: trainerUnit,
      target: trainerTarget,
    };
  }, [trainerEnabled, trainerStep, trainerInterval, trainerUnit, trainerTarget]);

  useEffect(() => {
    sessionConfigRef.current = { autoStopBars, autoStopSeconds, flashEnabled };
  }, [autoStopBars, autoStopSeconds, flashEnabled]);

  useEffect(() => {
    voiceCountRef.current = voiceCountEnabled;
  }, [voiceCountEnabled]);

  const stop = useCallback((): void => {
    engineRef.current?.stop();
    setPlaying(false);
    setCountingIn(false);
    setActiveBeat(0);
    setActiveSubdivision(0);
    setIsGapBar(false);
    setFlashActive(false);
    window.speechSynthesis.cancel();
  }, []);

  const handleTick = useCallback((event: TickEvent): void => {
    setActiveBeat(event.beat);
    setActiveSubdivision(event.subdivision);
    setCountingIn(event.countIn);
    setIsGapBar(!event.audible && !event.countIn);

    if (voiceCountRef.current && event.audible && event.subdivision === 0) {
      const count = new SpeechSynthesisUtterance(String(event.beat + 1));
      count.lang = 'ko-KR';
      count.rate = 1.8;
      count.volume = configRef.current.volume;
      window.speechSynthesis.cancel();
      window.speechSynthesis.speak(count);
    }

    if (event.subdivision === 0 && event.beat === 0 && sessionConfigRef.current.flashEnabled) {
      setFlashActive(true);
      window.setTimeout(() => setFlashActive(false), 90);
    }
    if (event.countIn || event.beat !== 0 || event.subdivision !== 0) {
      return;
    }

    const session = sessionConfigRef.current;
    if (session.autoStopBars > 0 && event.bar >= session.autoStopBars) {
      setBarCount(session.autoStopBars);
      window.setTimeout(stop, 0);
      return;
    }

    const nextBar = event.bar + 1;
    setBarCount(nextBar);

    const trainer = trainerConfigRef.current;
    if (
      trainer.enabled
      && trainer.unit === 'bars'
      && event.bar > 0
      && event.bar % trainer.interval === 0
      && lastTrainerBar.current !== event.bar
    ) {
      lastTrainerBar.current = event.bar;
      setBpm((current) => Math.min(trainer.target, clampBpm(current + trainer.step)));
    }
  }, [stop]);

  useEffect(() => {
    const engine = new MetronomeEngine(() => configRef.current, handleTick);
    engineRef.current = engine;
    return () => {
      engine.dispose();
      pitchContextRef.current?.close().catch(() => undefined);
      engineRef.current = null;
      pitchContextRef.current = null;
    };
  }, [handleTick]);

  useEffect(() => {
    if (!playing) {
      return undefined;
    }
    const timer = window.setInterval(() => {
      const nextElapsed = Math.floor((Date.now() - startedAt.current) / 1000);
      setElapsed(nextElapsed);
      const session = sessionConfigRef.current;
      if (session.autoStopSeconds > 0 && nextElapsed >= session.autoStopSeconds) {
        stop();
        return;
      }
      const trainer = trainerConfigRef.current;
      if (trainer.enabled && trainer.unit === 'seconds' && nextElapsed > 0) {
        const intervalIndex = Math.floor(nextElapsed / trainer.interval);
        if (intervalIndex > 0 && intervalIndex !== lastTrainerSecond.current) {
          lastTrainerSecond.current = intervalIndex;
          setBpm((current) => Math.min(trainer.target, clampBpm(current + trainer.step)));
        }
      }
    }, 250);
    return () => window.clearInterval(timer);
  }, [playing, stop]);

  useEffect(() => {
    localStorage.setItem(PRESET_STORAGE_KEY, JSON.stringify(presets));
  }, [presets]);

  useEffect(() => {
    const onFullscreenChange = (): void => setFullscreen(document.fullscreenElement !== null);
    document.addEventListener('fullscreenchange', onFullscreenChange);
    return () => document.removeEventListener('fullscreenchange', onFullscreenChange);
  }, []);

  const start = useCallback(async (): Promise<void> => {
    setBarCount(0);
    setElapsed(0);
    setCountingIn(countInBars > 0);
    lastTrainerBar.current = 0;
    lastTrainerSecond.current = 0;
    startedAt.current = Date.now();
    await engineRef.current?.start();
    setPlaying(true);
  }, [countInBars]);

  const togglePlayback = useCallback((): void => {
    if (playing) {
      stop();
    } else {
      void start();
    }
  }, [playing, start, stop]);

  const tapTempo = useCallback((): void => {
    const now = performance.now();
    const previous = tapTimes.current.at(-1);
    if (previous === undefined || now - previous > 2000) {
      tapTimes.current = [now];
      return;
    }
    tapTimes.current = [...tapTimes.current.slice(-7), now];
    const intervals = tapTimes.current.slice(1).map(
      (time, index) => time - tapTimes.current[index],
    );
    const average = intervals.reduce((sum, interval) => sum + interval, 0) / intervals.length;
    setBpm(clampBpm(60000 / average));
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent): void => {
      const target = event.target as HTMLElement | null;
      if (target?.matches('input, select, textarea, button, a, [contenteditable="true"]')) {
        return;
      }
      if (event.code === 'Space') {
        event.preventDefault();
        togglePlayback();
      } else if (event.key.toLowerCase() === 't') {
        event.preventDefault();
        tapTempo();
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
  }, [tapTempo, togglePlayback]);

  const changeBeatsPerBar = (value: number): void => {
    const next = clampInteger(value, 1, 13);
    setBeatsPerBar(next);
    setAccents((current) => Array.from(
      { length: next },
      (_, index) => current[index] ?? (index === 0 ? 2 : 1),
    ));
  };

  const cycleAccent = (index: number): void => {
    setAccents((current) => current.map(
      (level, beat) => beat === index ? ((level + 1) % 3) as AccentLevel : level,
    ));
  };

  const createPreset = (): TempoPreset => ({
    id: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
    name: presetName.trim() || `${bpm} BPM · ${beatsPerBar}/${denominator}`,
    bpm,
    beatsPerBar,
    denominator,
    subdivision,
    accents,
    sound,
    volume,
    pan,
    gapEnabled,
    audibleBars,
    mutedBars,
    countInBars,
  });

  const savePreset = (): void => {
    const preset = createPreset();
    setPresets((current) => [preset, ...current].slice(0, 100));
    setSelectedPresetId(preset.id);
    setPresetName('');
  };

  const overwritePreset = (): void => {
    if (!selectedPresetId) {
      savePreset();
      return;
    }
    const replacement = { ...createPreset(), id: selectedPresetId };
    setPresets((current) => current.map((preset) => preset.id === selectedPresetId ? replacement : preset));
    setPresetName('');
  };

  const loadPreset = useCallback((preset: TempoPreset): void => {
    setSelectedPresetId(preset.id);
    setBpm(clampBpm(preset.bpm));
    setBeatsPerBar(preset.beatsPerBar);
    setDenominator(preset.denominator);
    setSubdivision(preset.subdivision);
    setAccents(preset.accents);
    setSound(preset.sound);
    setVolume(preset.volume);
    setPan(preset.pan);
    setGapEnabled(preset.gapEnabled);
    setAudibleBars(preset.audibleBars);
    setMutedBars(preset.mutedBars);
    setCountInBars(preset.countInBars);
  }, []);

  const filteredPresets = useMemo(() => {
    const query = presetQuery.trim().toLocaleLowerCase('ko');
    return query
      ? presets.filter(({ name }) => name.toLocaleLowerCase('ko').includes(query))
      : presets;
  }, [presetQuery, presets]);

  const movePreset = (direction: -1 | 1): void => {
    if (presets.length === 0) {
      return;
    }
    const currentIndex = Math.max(0, presets.findIndex(({ id }) => id === selectedPresetId));
    const nextIndex = (currentIndex + direction + presets.length) % presets.length;
    const preset = presets[nextIndex];
    if (preset) {
      loadPreset(preset);
    }
  };

  const exportPresets = (): void => {
    const blob = new Blob([JSON.stringify(presets, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = 'score-lab-tempo-setlist.json';
    anchor.click();
    URL.revokeObjectURL(url);
  };

  const importPresets = async (event: React.ChangeEvent<HTMLInputElement>): Promise<void> => {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    try {
      localStorage.setItem(PRESET_STORAGE_KEY, await file.text());
      setPresets(readPresets());
    } finally {
      event.target.value = '';
    }
  };

  const toggleFullscreen = async (): Promise<void> => {
    if (document.fullscreenElement) {
      await document.exitFullscreen();
    } else {
      await document.querySelector('.tempo-stage')?.requestFullscreen();
    }
  };

  const playPitch = async (midi: number): Promise<void> => {
    pitchContextRef.current ??= new AudioContext();
    const context = pitchContextRef.current;
    await context.resume();
    const oscillator = context.createOscillator();
    const gain = context.createGain();
    const frequency = pitchCalibration * 2 ** ((midi - 69) / 12);
    oscillator.type = 'sine';
    oscillator.frequency.setValueAtTime(frequency, context.currentTime);
    gain.gain.setValueAtTime(0.0001, context.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.22, context.currentTime + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, context.currentTime + 0.8);
    oscillator.connect(gain);
    gain.connect(context.destination);
    oscillator.start();
    oscillator.stop(context.currentTime + 0.82);
  };

  const tempoMarking = bpm < 40 ? 'Larghissimo' : bpm < 60 ? 'Largo' : bpm < 76
    ? 'Adagio' : bpm < 108 ? 'Andante' : bpm < 120 ? 'Moderato'
      : bpm < 168 ? 'Allegro' : bpm < 200 ? 'Presto' : 'Prestissimo';

  return (
    <>
      <section className={`tempo-page ${isGapBar ? 'gap-active' : ''} ${flashActive ? 'is-flashing' : ''} ${leftPanelOpen ? '' : 'left-panel-closed'} ${rightPanelOpen ? '' : 'right-panel-closed'}`}>
        <aside className="tempo-sidebar" aria-hidden={!leftPanelOpen}>
          <div className="tempo-side-heading">
            <p>연습 도구</p>
            <h2>Practice Lab</h2>
          </div>

          <section className="practice-card">
            <div className="practice-title">
              <div><span className="practice-number">01</span><h3>카운트인 · 자동 정지</h3></div>
            </div>
            <p>연주 전 예비 박자를 듣고 정해진 시간 또는 마디에서 멈춥니다.</p>
            <div className="practice-fields three-fields">
              <label>카운트인<select value={countInBars} onChange={(event) => setCountInBars(Number(event.target.value))}><option value="0">없음</option><option value="1">1마디</option><option value="2">2마디</option></select></label>
              <label>정지 마디<input type="number" min="0" max="999" value={autoStopBars} onChange={(event) => setAutoStopBars(clampInteger(Number(event.target.value), 0, 999))} /></label>
              <label>정지 초<input type="number" min="0" max="3600" value={autoStopSeconds} onChange={(event) => setAutoStopSeconds(clampInteger(Number(event.target.value), 0, 3600))} /></label>
            </div>
          </section>

          <section className="practice-card">
            <div className="practice-title">
              <div><span className="practice-number">02</span><h3>갭 트레이너</h3></div>
              <button type="button" className={`switch ${gapEnabled ? 'on' : ''}`} onClick={() => setGapEnabled((current) => !current)} aria-pressed={gapEnabled} aria-label="갭 트레이너 켜기"><span /></button>
            </div>
            <p>클릭을 주기적으로 비워 내면의 박자를 점검합니다.</p>
            <div className="practice-fields">
              <label>클릭 마디<input type="number" min="1" max="16" value={audibleBars} onChange={(event) => setAudibleBars(clampInteger(Number(event.target.value), 1, 16))} /></label>
              <label>쉼 마디<input type="number" min="1" max="16" value={mutedBars} onChange={(event) => setMutedBars(clampInteger(Number(event.target.value), 1, 16))} /></label>
            </div>
          </section>

          <section className="practice-card">
            <div className="practice-title">
              <div><span className="practice-number">03</span><h3>스피드 트레이너</h3></div>
              <button type="button" className={`switch ${trainerEnabled ? 'on' : ''}`} onClick={() => setTrainerEnabled((current) => !current)} aria-pressed={trainerEnabled} aria-label="스피드 트레이너 켜기"><span /></button>
            </div>
            <p>마디 또는 시간 간격마다 목표 BPM까지 자동으로 올립니다.</p>
            <div className="trainer-unit">
              <button type="button" className={trainerUnit === 'bars' ? 'selected' : ''} onClick={() => setTrainerUnit('bars')}>마디 기준</button>
              <button type="button" className={trainerUnit === 'seconds' ? 'selected' : ''} onClick={() => setTrainerUnit('seconds')}>시간 기준</button>
            </div>
            <div className="practice-fields three-fields">
              <label>증가 BPM<input type="number" min="1" max="50" value={trainerStep} onChange={(event) => setTrainerStep(clampInteger(Number(event.target.value), 1, 50))} /></label>
              <label>{trainerUnit === 'bars' ? '반복 마디' : '반복 초'}<input type="number" min="1" max="999" value={trainerInterval} onChange={(event) => setTrainerInterval(clampInteger(Number(event.target.value), 1, 999))} /></label>
              <label>목표 BPM<input type="number" min={MIN_BPM} max={MAX_BPM} value={trainerTarget} onChange={(event) => setTrainerTarget(clampBpm(Number(event.target.value)))} /></label>
            </div>
          </section>

          <section className="preset-section">
            <div className="preset-heading"><h3>세트리스트</h3><span>{presets.length}/100</span></div>
            <div className="preset-create">
              <input value={presetName} onChange={(event) => setPresetName(event.target.value)} placeholder="곡 또는 연습 이름" maxLength={40} />
              <button type="button" onClick={savePreset} aria-label="현재 설정 저장">+</button>
            </div>
            <div className="preset-tools">
              <input value={presetQuery} onChange={(event) => setPresetQuery(event.target.value)} placeholder="프리셋 검색" />
              <button type="button" onClick={overwritePreset} disabled={!selectedPresetId}>덮어쓰기</button>
            </div>
            <div className="preset-actions">
              <button type="button" onClick={() => movePreset(-1)}>이전</button>
              <button type="button" onClick={() => movePreset(1)}>다음</button>
              <button type="button" onClick={exportPresets}>백업</button>
              <button type="button" onClick={() => importInputRef.current?.click()}>복원</button>
              <input ref={importInputRef} className="hidden-file-input" type="file" accept="application/json" onChange={(event) => void importPresets(event)} />
            </div>
            <div className="preset-list">
              {filteredPresets.length === 0 ? (
                <p className="preset-empty">저장된 프리셋이 없습니다.</p>
              ) : filteredPresets.map((preset) => (
                <div className={`preset-item ${selectedPresetId === preset.id ? 'selected' : ''}`} key={preset.id}>
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
          <button
            type="button"
            className="panel-toggle panel-toggle-left"
            onClick={() => setLeftPanelOpen((current) => !current)}
            aria-label={leftPanelOpen ? '연습 도구 패널 닫기' : '연습 도구 패널 열기'}
            aria-expanded={leftPanelOpen}
            title={leftPanelOpen ? '연습 도구 접기' : '연습 도구 펼치기'}
          >
            <span aria-hidden="true">{leftPanelOpen ? '‹' : '›'}</span>
          </button>
          <button
            type="button"
            className="panel-toggle panel-toggle-right"
            onClick={() => setRightPanelOpen((current) => !current)}
            aria-label={rightPanelOpen ? '박자 설정 패널 닫기' : '박자 설정 패널 열기'}
            aria-expanded={rightPanelOpen}
            title={rightPanelOpen ? '박자 설정 접기' : '박자 설정 펼치기'}
          >
            <span aria-hidden="true">{rightPanelOpen ? '›' : '‹'}</span>
          </button>
          <div className="tempo-status-row">
            <span className={playing ? 'live' : ''}><i />{playing ? countingIn ? 'COUNT IN' : isGapBar ? 'GAP · 박자 유지' : 'PLAYING' : 'READY'}</span>
            <div><span>{formatElapsed(elapsed)}</span><span>{barCount} 마디</span></div>
          </div>

          {displayMode === 'pendulum' && (
            <div className={`pendulum ${playing ? 'moving' : ''}`} style={{ '--beat-duration': `${60 / bpm}s` } as React.CSSProperties}><i /></div>
          )}

          <div className="beat-visual" aria-label={`${beatsPerBar}박 중 ${activeBeat + 1}박`}>
            {accents.map((level, index) => (
              <button type="button" key={index} className={`beat-dot level-${level} ${playing && activeBeat === index ? 'active' : ''}`} onClick={() => cycleAccent(index)} title="클릭하여 악센트 · 일반 · 음소거 전환">
                <span>{index + 1}</span><i />
              </button>
            ))}
          </div>

          <div className="bpm-control">
            <button type="button" onClick={() => setBpm((current) => clampBpm(current - 1))} aria-label="BPM 1 감소">−</button>
            <label>
              <input type="number" min={MIN_BPM} max={MAX_BPM} value={bpm} onChange={(event) => setBpm(clampBpm(Number(event.target.value)))} aria-label="분당 박자" />
              <span>BPM</span><small>{tempoMarking}</small>
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

        <aside className="tempo-controls" aria-hidden={!rightPanelOpen}>
          <section className="control-section">
            <div className="control-heading"><span>01</span><div><h3>박자표</h3><p>단순·복합·변박 구성</p></div></div>
            <div className="signature-control">
              <div><button type="button" onClick={() => changeBeatsPerBar(beatsPerBar + 1)}>⌃</button><strong>{beatsPerBar}</strong><button type="button" onClick={() => changeBeatsPerBar(beatsPerBar - 1)}>⌄</button></div>
              <i />
              <div><button type="button" onClick={() => setDenominator(denominator === 2 ? 4 : denominator === 4 ? 8 : 2)}>⌃</button><strong>{denominator}</strong><button type="button" onClick={() => setDenominator(denominator === 8 ? 4 : denominator === 4 ? 2 : 8)}>⌄</button></div>
            </div>
          </section>

          <section className="control-section">
            <div className="control-heading"><span>02</span><div><h3>리듬 분할</h3><p>한 박을 나누는 단위</p></div></div>
            <div className="subdivision-grid">
              {SUBDIVISION_OPTIONS.map(([value, symbol, label]) => (
                <button type="button" key={value} className={subdivision === value ? 'selected' : ''} onClick={() => setSubdivision(value)}><strong>{symbol}</strong><span>{label}</span></button>
              ))}
            </div>
          </section>

          <section className="control-section sound-section">
            <div className="control-heading"><span>03</span><div><h3>사운드</h3><p>14종 클릭 음색과 출력</p></div></div>
            <div className="sound-select-row">
              <select value={sound} onChange={(event) => setSound(event.target.value as ClickSound)}>
                {SOUND_OPTIONS.map(([value, label]) => <option value={value} key={value}>{label}</option>)}
              </select>
              <button type="button" className={muted ? 'selected' : ''} onClick={() => setMuted((current) => !current)}>{muted ? '음소거됨' : '음소거'}</button>
              <button type="button" className={voiceCountEnabled ? 'selected' : ''} onClick={() => setVoiceCountEnabled((current) => !current)}>음성 카운트</button>
            </div>
            <label className="volume-control"><span>볼륨</span><input type="range" min="0" max="1" step="0.01" value={volume} onChange={(event) => setVolume(Number(event.target.value))} /><output>{Math.round(volume * 100)}</output></label>
            <label className="volume-control"><span>패닝</span><input type="range" min="-1" max="1" step="0.01" value={pan} onChange={(event) => setPan(Number(event.target.value))} /><output>{pan === 0 ? 'C' : pan < 0 ? `L${Math.round(Math.abs(pan) * 100)}` : `R${Math.round(pan * 100)}`}</output></label>
          </section>

          <section className="control-section display-section">
            <div className="control-heading"><span>04</span><div><h3>표시</h3><p>연습 중 시각 피드백</p></div></div>
            <div className="display-options">
              <button type="button" className={displayMode === 'led' ? 'selected' : ''} onClick={() => setDisplayMode('led')}>LED</button>
              <button type="button" className={displayMode === 'pendulum' ? 'selected' : ''} onClick={() => setDisplayMode('pendulum')}>펜듈럼</button>
              <button type="button" className={flashEnabled ? 'selected' : ''} onClick={() => setFlashEnabled((current) => !current)}>화면 플래시</button>
              <button type="button" className={fullscreen ? 'selected' : ''} onClick={() => void toggleFullscreen()}>전체 화면</button>
            </div>
          </section>

          <section className="control-section pitch-section">
            <div className="control-heading"><span>05</span><div><h3>피치 파이프</h3><p>12음 기준음 · A4 보정</p></div></div>
            <div className="pitch-pipe">
              {PITCHES.map(([name, midi]) => <button type="button" key={name} onClick={() => void playPitch(midi)}>{name}</button>)}
            </div>
            <label className="pitch-calibration"><span>A4</span><input type="range" min="414" max="466" value={pitchCalibration} onChange={(event) => setPitchCalibration(Number(event.target.value))} /><output>{pitchCalibration} Hz</output></label>
          </section>

          <div className="accent-legend"><span><i className="accent" />악센트</span><span><i />일반</span><span><i className="muted" />음소거</span></div>
        </aside>
      </section>

      <footer className="tempo-footer">
        <span>박자를 눌러 악센트를 변경할 수 있습니다.</span>
        <div><kbd>T</kbd> 탭 템포 <kbd>↑↓</kbd> 1 BPM <kbd>Shift + ↑↓</kbd> 5 BPM</div>
      </footer>
    </>
  );
}
