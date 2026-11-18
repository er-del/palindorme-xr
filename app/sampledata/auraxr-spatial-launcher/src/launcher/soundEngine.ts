import {type TrackInfo} from './types';

/**
 * Procedural Web Audio synthesizer for subtle spatial OS feedback chimes
 * and ambient spatial music playback (zero external audio asset dependencies).
 */
export class SoundEngine {
  private ctx: AudioContext | null = null;
  private enabled = true;
  private musicGain: GainNode | null = null;
  private musicTimer: number | null = null;
  private musicPlaying = false;
  private musicVolume = 0.45;
  private currentTrack: TrackInfo | null = null;
  private chordStep = 0;

  setEnabled(enabled: boolean): void {
    this.enabled = enabled;
    if (!enabled && this.musicPlaying) {
      this.stopMusic();
    }
  }

  isEnabled(): boolean {
    return this.enabled;
  }

  private ensureContext(): AudioContext | null {
    if (typeof window === 'undefined') return null;
    if (!this.ctx) {
      const AudioCtx =
        window.AudioContext ||
        (window as unknown as {webkitAudioContext?: typeof AudioContext})
          .webkitAudioContext;
      if (!AudioCtx) return null;
      this.ctx = new AudioCtx();
    }
    if (this.ctx.state === 'suspended') {
      void this.ctx.resume().catch(() => {});
    }
    return this.ctx;
  }

  playTap(): void {
    if (!this.enabled) return;
    const ctx = this.ensureContext();
    if (!ctx) return;
    const now = ctx.currentTime;
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(760, now);
    osc.frequency.exponentialRampToValueAtTime(1040, now + 0.045);
    gain.gain.setValueAtTime(0.06, now);
    gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.065);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start(now);
    osc.stop(now + 0.07);
  }

  playOpen(): void {
    if (!this.enabled) return;
    const ctx = this.ensureContext();
    if (!ctx) return;
    const now = ctx.currentTime;
    [523.25, 659.25].forEach((freq, idx) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'triangle';
      const t = now + idx * 0.045;
      osc.frequency.setValueAtTime(freq, t);
      gain.gain.setValueAtTime(0.055, t);
      gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.16);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(t);
      osc.stop(t + 0.17);
    });
  }

  playClose(): void {
    if (!this.enabled) return;
    const ctx = this.ensureContext();
    if (!ctx) return;
    const now = ctx.currentTime;
    [587.33, 440].forEach((freq, idx) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      const t = now + idx * 0.04;
      osc.frequency.setValueAtTime(freq, t);
      gain.gain.setValueAtTime(0.05, t);
      gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.13);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(t);
      osc.stop(t + 0.14);
    });
  }

  playEnvironmentShift(): void {
    if (!this.enabled) return;
    const ctx = this.ensureContext();
    if (!ctx) return;
    const now = ctx.currentTime;
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(220, now);
    osc.frequency.exponentialRampToValueAtTime(330, now + 0.28);
    gain.gain.setValueAtTime(0.04, now);
    gain.gain.exponentialRampToValueAtTime(0.0001, now + 0.35);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start(now);
    osc.stop(now + 0.36);
  }

  startMusic(track: TrackInfo): void {
    this.currentTrack = track;
    if (!this.enabled) return;
    const ctx = this.ensureContext();
    if (!ctx) return;
    this.stopMusic();
    this.currentTrack = track;
    this.musicPlaying = true;
    this.chordStep = 0;

    this.musicGain = ctx.createGain();
    this.musicGain.gain.value = this.musicVolume * 0.16;
    this.musicGain.connect(ctx.destination);

    const scheduleStep = () => {
      if (!this.musicPlaying || !this.currentTrack || !this.ctx || !this.musicGain) {
        return;
      }
      const now = this.ctx.currentTime;
      const intervals = this.currentTrack.chordIntervals;
      const semitone = intervals[this.chordStep % intervals.length];
      const transpose = Math.floor(this.chordStep / intervals.length) % 2 === 1 ? 5 : 0;
      const freq =
        this.currentTrack.rootHz * Math.pow(2, (semitone + transpose) / 12);

      const osc = this.ctx.createOscillator();
      const noteGain = this.ctx.createGain();
      osc.type = 'sine';
      osc.frequency.setValueAtTime(freq, now);
      noteGain.gain.setValueAtTime(0.001, now);
      noteGain.gain.linearRampToValueAtTime(0.7, now + 0.18);
      noteGain.gain.exponentialRampToValueAtTime(0.001, now + 1.45);
      osc.connect(noteGain);
      noteGain.connect(this.musicGain);
      osc.start(now);
      osc.stop(now + 1.5);
      this.chordStep++;
    };

    scheduleStep();
    const beatMs = Math.max(450, Math.round((60 / track.bpm) * 1000));
    this.musicTimer = window.setInterval(scheduleStep, beatMs);
  }

  setMusicVolume(volume: number): void {
    this.musicVolume = Math.max(0, Math.min(1, volume));
    if (this.musicGain) {
      this.musicGain.gain.value = this.musicVolume * 0.16;
    }
  }

  stopMusic(): void {
    this.musicPlaying = false;
    if (this.musicTimer !== null) {
      window.clearInterval(this.musicTimer);
      this.musicTimer = null;
    }
    if (this.musicGain) {
      this.musicGain.disconnect();
      this.musicGain = null;
    }
  }

  dispose(): void {
    this.stopMusic();
    if (this.ctx) {
      void this.ctx.close().catch(() => {});
      this.ctx = null;
    }
  }
}
