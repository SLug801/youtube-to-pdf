export type AccentLevel = 0 | 1 | 2;

export type ClickSound = 'classic' | 'wood' | 'electronic';

export interface MetronomeConfig {
  bpm: number;
  beatsPerBar: number;
  subdivision: number;
  accents: AccentLevel[];
  volume: number;
  sound: ClickSound;
  gapEnabled: boolean;
  audibleBars: number;
  mutedBars: number;
}

export interface TickEvent {
  bar: number;
  beat: number;
  subdivision: number;
  audible: boolean;
}

type ConfigReader = () => MetronomeConfig;
type TickListener = (event: TickEvent) => void;

const LOOK_AHEAD_MS = 25;
const SCHEDULE_AHEAD_SECONDS = 0.1;

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
    const gapCycle = Math.max(1, config.audibleBars + config.mutedBars);
    const audible = !config.gapEnabled
      || this.barIndex % gapCycle < config.audibleBars;
    const visualEvent: TickEvent = {
      bar: this.barIndex,
      beat,
      subdivision,
      audible,
    };

    if (audible) {
      const level = subdivision === 0 ? config.accents[beat] ?? 1 : 1;
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
    const oscillator = this.context.createOscillator();
    const gain = this.context.createGain();
    const soundFrequencies: Record<ClickSound, [number, number, OscillatorType]> = {
      classic: [920, 1480, 'sine'],
      wood: [640, 1050, 'triangle'],
      electronic: [1200, 1880, 'square'],
    };
    const [normalFrequency, accentFrequency, wave] = soundFrequencies[config.sound];
    oscillator.type = wave;
    oscillator.frequency.setValueAtTime(
      level === 2 ? accentFrequency : normalFrequency,
      at,
    );

    const baseGain = config.volume * (isSubdivision ? 0.22 : level === 2 ? 0.72 : 0.46);
    gain.gain.setValueAtTime(Math.max(0.0001, baseGain), at);
    gain.gain.exponentialRampToValueAtTime(0.0001, at + (config.sound === 'wood' ? 0.045 : 0.03));
    oscillator.connect(gain);
    gain.connect(this.context.destination);
    oscillator.start(at);
    oscillator.stop(at + 0.05);
  }
}
