export type AccentLevel = 0 | 1 | 2;

export type ClickSound =
  | 'classic'
  | 'wood'
  | 'electronic'
  | 'studio'
  | 'live'
  | 'rim'
  | 'clave'
  | 'cowbell'
  | 'hihat'
  | 'shaker'
  | 'beep'
  | 'pulse'
  | 'soft'
  | 'deep';

export interface MetronomeConfig {
  bpm: number;
  beatsPerBar: number;
  subdivision: number;
  accents: AccentLevel[];
  volume: number;
  sound: ClickSound;
  pan: number;
  muted: boolean;
  gapEnabled: boolean;
  audibleBars: number;
  mutedBars: number;
  countInBars: number;
}

export interface TickEvent {
  bar: number;
  beat: number;
  subdivision: number;
  audible: boolean;
  countIn: boolean;
}

interface SoundProfile {
  normal: number;
  accent: number;
  wave: OscillatorType;
  duration: number;
  level: number;
}

type ConfigReader = () => MetronomeConfig;
type TickListener = (event: TickEvent) => void;

const LOOK_AHEAD_MS = 25;
const SCHEDULE_AHEAD_SECONDS = 0.1;

const SOUND_PROFILES: Record<ClickSound, SoundProfile> = {
  classic: { normal: 920, accent: 1480, wave: 'sine', duration: 0.03, level: 1 },
  wood: { normal: 640, accent: 1050, wave: 'triangle', duration: 0.045, level: 0.95 },
  electronic: { normal: 1200, accent: 1880, wave: 'square', duration: 0.03, level: 0.72 },
  studio: { normal: 1080, accent: 1620, wave: 'triangle', duration: 0.025, level: 0.9 },
  live: { normal: 1500, accent: 2300, wave: 'square', duration: 0.022, level: 0.8 },
  rim: { normal: 1350, accent: 2050, wave: 'triangle', duration: 0.018, level: 0.86 },
  clave: { normal: 1760, accent: 2480, wave: 'sine', duration: 0.026, level: 0.82 },
  cowbell: { normal: 560, accent: 840, wave: 'square', duration: 0.055, level: 0.62 },
  hihat: { normal: 3400, accent: 4700, wave: 'sawtooth', duration: 0.016, level: 0.42 },
  shaker: { normal: 2700, accent: 3600, wave: 'sawtooth', duration: 0.012, level: 0.35 },
  beep: { normal: 880, accent: 1320, wave: 'sine', duration: 0.06, level: 0.82 },
  pulse: { normal: 440, accent: 660, wave: 'square', duration: 0.035, level: 0.58 },
  soft: { normal: 720, accent: 1120, wave: 'sine', duration: 0.055, level: 0.7 },
  deep: { normal: 220, accent: 330, wave: 'triangle', duration: 0.075, level: 0.9 },
};

export class MetronomeEngine {
  private context: AudioContext | null = null;
  private timer: number | null = null;
  private nextTickTime = 0;
  private tickIndex = 0;
  private barIndex = 0;
  private pendingVisuals = new Set<number>();

  public constructor(
    private readonly readConfig: ConfigReader,
    private readonly onTick: TickListener,
  ) {}

  public async start(): Promise<void> {
    if (this.timer !== null) {
      return;
    }
    this.context ??= new AudioContext();
    await this.context.resume();
    this.tickIndex = 0;
    this.barIndex = 0;
    this.nextTickTime = this.context.currentTime + 0.06;
    this.schedule();
    this.timer = window.setInterval(() => this.schedule(), LOOK_AHEAD_MS);
  }

  public stop(): void {
    if (this.timer !== null) {
      window.clearInterval(this.timer);
      this.timer = null;
    }
    for (const timeout of this.pendingVisuals) {
      window.clearTimeout(timeout);
    }
    this.pendingVisuals.clear();
    this.tickIndex = 0;
    this.barIndex = 0;
  }

  public dispose(): void {
    this.stop();
    if (this.context) {
      void this.context.close();
      this.context = null;
    }
  }

  private schedule(): void {
    if (!this.context) {
      return;
    }
    while (this.nextTickTime < this.context.currentTime + SCHEDULE_AHEAD_SECONDS) {
      this.scheduleTick(this.nextTickTime);
      const { bpm, subdivision } = this.readConfig();
      this.nextTickTime += 60 / Math.max(1, bpm) / Math.max(1, subdivision);
    }
  }

  private scheduleTick(at: number): void {
    if (!this.context) {
      return;
    }
    const config = this.readConfig();
    const ticksPerBar = config.beatsPerBar * config.subdivision;
    const beat = Math.floor(this.tickIndex / config.subdivision);
    const subdivision = this.tickIndex % config.subdivision;
    const countIn = this.barIndex < config.countInBars;
    const practiceBar = Math.max(0, this.barIndex - config.countInBars);
    const gapCycle = Math.max(1, config.audibleBars + config.mutedBars);
    const audible = countIn || !config.gapEnabled
      || practiceBar % gapCycle < config.audibleBars;
    const visualEvent: TickEvent = {
      bar: practiceBar,
      beat,
      subdivision,
      audible,
      countIn,
    };

    if (audible && !config.muted) {
      const level = subdivision === 0
        ? countIn && beat === 0 ? 2 : config.accents[beat] ?? 1
        : 1;
      if (level > 0) {
        this.createClick(at, level, subdivision > 0, config);
      }
    }

    const delay = Math.max(0, (at - this.context.currentTime) * 1000);
    const timeout = window.setTimeout(() => {
      this.pendingVisuals.delete(timeout);
      this.onTick(visualEvent);
    }, delay);
    this.pendingVisuals.add(timeout);

    this.tickIndex += 1;
    if (this.tickIndex >= ticksPerBar) {
      this.tickIndex = 0;
      this.barIndex += 1;
    }
  }

  private createClick(
    at: number,
    level: AccentLevel,
    isSubdivision: boolean,
    config: MetronomeConfig,
  ): void {
    if (!this.context) {
      return;
    }
    const profile = SOUND_PROFILES[config.sound];
    const oscillator = this.context.createOscillator();
    const gain = this.context.createGain();
    const panner = this.context.createStereoPanner();
    oscillator.type = profile.wave;
    oscillator.frequency.setValueAtTime(
      level === 2 ? profile.accent : profile.normal,
      at,
    );

    const emphasis = isSubdivision ? 0.22 : level === 2 ? 0.72 : 0.46;
    const baseGain = config.volume * profile.level * emphasis;
    gain.gain.setValueAtTime(Math.max(0.0001, baseGain), at);
    gain.gain.exponentialRampToValueAtTime(0.0001, at + profile.duration);
    panner.pan.setValueAtTime(Math.min(1, Math.max(-1, config.pan)), at);
    oscillator.connect(gain);
    gain.connect(panner);
    panner.connect(this.context.destination);
    oscillator.start(at);
    oscillator.stop(at + profile.duration + 0.01);
  }
}
