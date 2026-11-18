import {
  type AppCategory,
  type AppDefinition,
  type AppId,
  type EnvironmentId,
  type EnvironmentPreset,
  type LauncherSnapshot,
  type LauncherTab,
  type NoteItem,
  type ThemePreset,
  type TrackInfo,
  type WindowPose,
  type WindowState,
} from './types';

export const APP_CATALOG: readonly AppDefinition[] = [
  {
    id: 'browser',
    name: 'Browser',
    subtitle: 'Spatial Web & Docs',
    icon: 'public',
    accentColor: '#3b82f6',
    category: 'system',
    installedByDefault: true,
    defaultSize: {width: 0.72, height: 0.56},
  },
  {
    id: 'photos',
    name: 'Photos',
    subtitle: 'Spatial Memories',
    icon: 'photo_library',
    accentColor: '#ec4899',
    category: 'media',
    installedByDefault: true,
    defaultSize: {width: 0.68, height: 'auto'},
  },
  {
    id: 'music',
    name: 'Music',
    subtitle: 'Spatial Audio Synth',
    icon: 'music_note',
    accentColor: '#ef4444',
    category: 'media',
    installedByDefault: true,
    defaultSize: {width: 0.56, height: 'auto'},
  },
  {
    id: 'notes',
    name: 'Notes',
    subtitle: 'Quick Spatial Notes',
    icon: 'edit_note',
    accentColor: '#f59e0b',
    category: 'tools',
    installedByDefault: true,
    defaultSize: {width: 0.56, height: 0.5},
  },
  {
    id: 'files',
    name: 'Files',
    subtitle: 'Android Storage & APKs',
    icon: 'folder',
    accentColor: '#0ea5e9',
    category: 'system',
    installedByDefault: true,
    defaultSize: {width: 0.66, height: 0.5},
  },
  {
    id: 'studio3d',
    name: '3D Studio',
    subtitle: 'Spatial Object Inspector',
    icon: 'view_in_ar',
    accentColor: '#8b5cf6',
    category: 'media',
    installedByDefault: true,
    defaultSize: {width: 0.54, height: 'auto'},
  },
  {
    id: 'clock',
    name: 'Clock',
    subtitle: 'World Time & Timer',
    icon: 'schedule',
    accentColor: '#14b8a6',
    category: 'tools',
    installedByDefault: true,
    defaultSize: {width: 0.48, height: 'auto'},
  },
  {
    id: 'store',
    name: 'App Store',
    subtitle: 'Android XR Packages',
    icon: 'apps',
    accentColor: '#10b981',
    category: 'system',
    installedByDefault: true,
    defaultSize: {width: 0.66, height: 0.52},
  },
  {
    id: 'calculator',
    name: 'Calculator',
    subtitle: 'Spatial Math Pad',
    icon: 'calculate',
    accentColor: '#f97316',
    category: 'tools',
    installedByDefault: false,
    defaultSize: {width: 0.42, height: 'auto'},
  },
  {
    id: 'terminal',
    name: 'ADB Shell',
    subtitle: 'Android XR Bridge',
    icon: 'terminal',
    accentColor: '#22c55e',
    category: 'system',
    installedByDefault: false,
    defaultSize: {width: 0.64, height: 0.48},
  },
  {
    id: 'weather',
    name: 'Weather',
    subtitle: 'Atmospheric Forecast',
    icon: 'partly_cloudy_day',
    accentColor: '#38bdf8',
    category: 'tools',
    installedByDefault: false,
    defaultSize: {width: 0.52, height: 'auto'},
  },
];

export const ENVIRONMENT_PRESETS: readonly EnvironmentPreset[] = [
  {
    id: 'passthrough',
    name: 'Passthrough',
    subtitle: 'Mixed Reality Room',
    icon: 'visibility',
    horizonColor: 0x0b101b,
    accentHex: '#60a5fa',
  },
  {
    id: 'alpine',
    name: 'Mount Haleakala',
    subtitle: 'Twilight Volcanic Ridge',
    icon: 'landscape',
    horizonColor: 0x1a1c30,
    accentHex: '#f59e0b',
  },
  {
    id: 'lunar',
    name: 'Lunar Horizon',
    subtitle: 'Quiet Crater & Earthrise',
    icon: 'dark_mode',
    horizonColor: 0x050812,
    accentHex: '#818cf8',
  },
  {
    id: 'obsidian',
    name: 'Midnight Studio',
    subtitle: 'Architectural Cinema',
    icon: 'theater_comedy',
    horizonColor: 0x090b10,
    accentHex: '#38bdf8',
  },
];

export const MUSIC_TRACKS: readonly TrackInfo[] = [
  {
    id: 'horizon',
    title: 'Horizon Glass',
    artist: 'Spatial Ambient',
    bpm: 72,
    rootHz: 220,
    chordIntervals: [0, 4, 7, 11],
    palette: ['#0f172a', '#2563eb', '#93c5fd'],
  },
  {
    id: 'haleakala',
    title: 'Twilight Ridge',
    artist: 'Altitude Echoes',
    bpm: 64,
    rootHz: 196,
    chordIntervals: [0, 3, 7, 10],
    palette: ['#1c1917', '#d97706', '#fde68a'],
  },
  {
    id: 'lunar',
    title: 'Mare Tranquillitatis',
    artist: 'Orbital Drift',
    bpm: 58,
    rootHz: 261.63,
    chordIntervals: [0, 5, 7, 12],
    palette: ['#020617', '#4f46e5', '#c7d2fe'],
  },
];

export const DEFAULT_NOTES: readonly NoteItem[] = [
  {
    id: 'note-1',
    title: 'Android XR Workspace Setup',
    body: 'Pin Browser on left arc, 3D Studio center-right, and switch to Mount Haleakala at 85% immersion.',
    done: false,
  },
  {
    id: 'note-2',
    title: 'Spatial Gesture Reminder',
    body: 'Pinch any window edge bar to reposition in 3D space or pull closer.',
    done: true,
  },
  {
    id: 'note-3',
    title: 'APK Spatialization Queue',
    body: 'Install Calculator and ADB Shell from the Spatial App Store.',
    done: false,
  },
];

const DEFAULT_PINNED_DOCK: readonly AppId[] = [
  'browser',
  'photos',
  'music',
  'studio3d',
  'notes',
];

const MAX_DOCK_PINS = 6;
const MAX_WINDOW_SLOTS = 6;

export function getAppDefinition(id: AppId): AppDefinition {
  const found = APP_CATALOG.find((app) => app.id === id);
  if (!found) {
    throw new Error(`Unknown app id: ${id}`);
  }
  return found;
}

export function getEnvironmentPreset(id: EnvironmentId): EnvironmentPreset {
  const found = ENVIRONMENT_PRESETS.find((env) => env.id === id);
  if (!found) {
    throw new Error(`Unknown environment id: ${id}`);
  }
  return found;
}

/**
 * Computes an ergonomic Vision Pro-style curved arc pose in world space
 * around the user's forward view (x=0, eye y=1.42, z=-1.18).
 */
export function computeWindowArcPose(slotIndex: number): WindowPose {
  // Ordered ergonomic slots around center-front so up to 4 windows fan cleanly without overlap
  const slotAnglesDeg = [-12, 12, -33, 33, -22, 22];
  const angleDeg = slotAnglesDeg[slotIndex % slotAnglesDeg.length];
  const angleRad = (angleDeg * Math.PI) / 180;
  const radius = 1.84;
  const ringOffset = Math.floor(slotIndex / slotAnglesDeg.length) * 0.14;
  const effectiveRadius = radius + ringOffset;
  const x = Math.sin(angleRad) * effectiveRadius;
  const z = -Math.cos(angleRad) * effectiveRadius + 0.04;
  const y = 1.46 + (slotIndex % 2 === 0 ? 0.02 : -0.02);
  return {
    x: Number(x.toFixed(3)),
    y: Number(y.toFixed(3)),
    z: Number(z.toFixed(3)),
    rotY: Number((-angleRad * 0.85).toFixed(3)),
  };
}

export function formatElapsedStopwatch(totalMs: number): string {
  const safeMs = Math.max(0, Math.floor(totalMs));
  const minutes = Math.floor(safeMs / 60000);
  const seconds = Math.floor((safeMs % 60000) / 1000);
  const tenths = Math.floor((safeMs % 1000) / 100);
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}.${tenths}`;
}

export function evaluateCalculatorExpression(
  left: number,
  op: '+' | '-' | '×' | '÷',
  right: number
): string {
  let result: number;
  switch (op) {
    case '+':
      result = left + right;
      break;
    case '-':
      result = left - right;
      break;
    case '×':
      result = left * right;
      break;
    case '÷':
      if (right === 0) return 'Error';
      result = left / right;
      break;
  }
  if (!Number.isFinite(result)) return 'Error';
  return Number(result.toPrecision(10)).toString();
}

export class LauncherStateStore {
  private state: LauncherSnapshot;
  private focusCounter = 1;
  private listeners = new Set<(snapshot: LauncherSnapshot) => void>();

  constructor() {
    this.state = {
      activeTab: 'apps',
      activeCategory: 'all',
      homeVisible: true,
      controlCenterVisible: false,
      installedApps: APP_CATALOG.filter((a) => a.installedByDefault).map(
        (a) => a.id
      ),
      pinnedDockApps: [...DEFAULT_PINNED_DOCK],
      openWindows: [],
      settings: {
        theme: 'grayGlass',
        soundEnabled: true,
        handRaysVisible: false,
        wifiConnected: true,
        bluetoothConnected: true,
        doNotDisturb: false,
        immersion: 0,
        environment: 'passthrough',
      },
    };
  }

  getSnapshot(): LauncherSnapshot {
    return {
      ...this.state,
      installedApps: [...this.state.installedApps],
      pinnedDockApps: [...this.state.pinnedDockApps],
      openWindows: this.state.openWindows.map((w) => ({...w})),
      settings: {...this.state.settings},
    };
  }

  subscribe(listener: (snapshot: LauncherSnapshot) => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private emit(): void {
    const snap = this.getSnapshot();
    for (const listener of this.listeners) {
      listener(snap);
    }
  }

  setTab(tab: LauncherTab): void {
    if (this.state.activeTab === tab) return;
    this.state.activeTab = tab;
    this.state.homeVisible = true;
    this.emit();
  }

  setCategory(category: 'all' | AppCategory): void {
    if (this.state.activeCategory === category) return;
    this.state.activeCategory = category;
    this.emit();
  }

  toggleHome(visible?: boolean): void {
    const next = visible ?? !this.state.homeVisible;
    if (this.state.homeVisible === next) return;
    this.state.homeVisible = next;
    this.emit();
  }

  toggleControlCenter(visible?: boolean): void {
    const next = visible ?? !this.state.controlCenterVisible;
    if (this.state.controlCenterVisible === next) return;
    this.state.controlCenterVisible = next;
    this.emit();
  }

  openApp(id: AppId): WindowState {
    if (!this.state.installedApps.includes(id)) {
      this.state.installedApps.push(id);
    }
    const existing = this.state.openWindows.find((w) => w.id === id);
    if (existing) {
      existing.focusedAt = ++this.focusCounter;
      this.state.homeVisible = false;
      this.emit();
      return {...existing};
    }

    const usedSlots = new Set(this.state.openWindows.map((w) => w.slotIndex));
    let slotIndex = 0;
    while (usedSlots.has(slotIndex) && slotIndex < MAX_WINDOW_SLOTS) {
      slotIndex++;
    }
    if (this.state.openWindows.length >= MAX_WINDOW_SLOTS) {
      // Replace the oldest unfocused window to keep spatial desk clean
      const sorted = [...this.state.openWindows].sort(
        (a, b) => a.focusedAt - b.focusedAt
      );
      const oldest = sorted[0];
      slotIndex = oldest.slotIndex;
      this.state.openWindows = this.state.openWindows.filter(
        (w) => w.id !== oldest.id
      );
    }

    const created: WindowState = {
      id,
      slotIndex,
      pinnedToHead: false,
      focusedAt: ++this.focusCounter,
    };
    this.state.openWindows.push(created);
    this.state.homeVisible = false;
    this.emit();
    return {...created};
  }

  closeApp(id: AppId): boolean {
    const before = this.state.openWindows.length;
    this.state.openWindows = this.state.openWindows.filter((w) => w.id !== id);
    if (this.state.openWindows.length === before) return false;
    this.emit();
    return true;
  }

  closeAllApps(): void {
    if (this.state.openWindows.length === 0) return;
    this.state.openWindows = [];
    this.emit();
  }

  togglePinWindowToHead(id: AppId): boolean | undefined {
    const win = this.state.openWindows.find((w) => w.id === id);
    if (!win) return undefined;
    win.pinnedToHead = !win.pinnedToHead;
    win.focusedAt = ++this.focusCounter;
    this.emit();
    return win.pinnedToHead;
  }

  installApp(id: AppId): boolean {
    if (this.state.installedApps.includes(id)) return false;
    this.state.installedApps.push(id);
    if (this.state.pinnedDockApps.length < MAX_DOCK_PINS) {
      this.state.pinnedDockApps.push(id);
    }
    this.emit();
    return true;
  }

  uninstallApp(id: AppId): boolean {
    const def = getAppDefinition(id);
    if (def.installedByDefault) return false;
    const before = this.state.installedApps.length;
    this.state.installedApps = this.state.installedApps.filter((a) => a !== id);
    this.state.pinnedDockApps = this.state.pinnedDockApps.filter(
      (a) => a !== id
    );
    this.state.openWindows = this.state.openWindows.filter((w) => w.id !== id);
    if (this.state.installedApps.length === before) return false;
    this.emit();
    return true;
  }

  togglePinToDock(id: AppId): boolean {
    if (this.state.pinnedDockApps.includes(id)) {
      this.state.pinnedDockApps = this.state.pinnedDockApps.filter(
        (a) => a !== id
      );
      this.emit();
      return false;
    }
    if (this.state.pinnedDockApps.length >= MAX_DOCK_PINS) {
      this.state.pinnedDockApps.shift();
    }
    this.state.pinnedDockApps.push(id);
    this.emit();
    return true;
  }

  setEnvironment(environment: EnvironmentId): void {
    this.state.settings.environment = environment;
    if (environment === 'passthrough') {
      this.state.settings.immersion = 0;
    } else if (this.state.settings.immersion === 0) {
      this.state.settings.immersion = 0.85;
    }
    this.emit();
  }

  setImmersion(immersion: number): void {
    const clamped = Math.max(0, Math.min(1, Number(immersion.toFixed(2))));
    this.state.settings.immersion = clamped;
    if (clamped > 0 && this.state.settings.environment === 'passthrough') {
      this.state.settings.environment = 'alpine';
    } else if (clamped === 0) {
      this.state.settings.environment = 'passthrough';
    }
    this.emit();
  }

  setTheme(theme: ThemePreset): void {
    if (this.state.settings.theme === theme) return;
    this.state.settings.theme = theme;
    this.emit();
  }

  toggleSetting(
    key: 'soundEnabled' | 'handRaysVisible' | 'wifiConnected' | 'bluetoothConnected' | 'doNotDisturb'
  ): boolean {
    const next = !this.state.settings[key];
    this.state.settings[key] = next;
    this.emit();
    return next;
  }

  getFilteredInstalledApps(): AppDefinition[] {
    const installedSet = new Set(this.state.installedApps);
    return APP_CATALOG.filter((app) => {
      if (!installedSet.has(app.id)) return false;
      if (
        this.state.activeCategory !== 'all' &&
        app.category !== this.state.activeCategory
      ) {
        return false;
      }
      return true;
    });
  }

  resetWorkspace(): void {
    this.state.activeTab = 'apps';
    this.state.activeCategory = 'all';
    this.state.homeVisible = true;
    this.state.controlCenterVisible = false;
    this.state.openWindows = [];
    this.state.settings.environment = 'passthrough';
    this.state.settings.immersion = 0;
    this.state.settings.theme = 'grayGlass';
    this.emit();
  }
}
