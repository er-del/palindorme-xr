export type AppId =
  | 'browser'
  | 'photos'
  | 'music'
  | 'notes'
  | 'files'
  | 'studio3d'
  | 'clock'
  | 'store'
  | 'calculator'
  | 'terminal'
  | 'weather';

export type AppCategory = 'system' | 'media' | 'tools';

export type LauncherTab = 'apps' | 'environments' | 'widgets' | 'settings';

export type EnvironmentId = 'passthrough' | 'alpine' | 'lunar' | 'obsidian';

export type ThemePreset = 'grayGlass' | 'glimmer' | 'highContrast';

export interface AppDefinition {
  id: AppId;
  name: string;
  subtitle: string;
  icon: string;
  accentColor: string;
  category: AppCategory;
  installedByDefault: boolean;
  defaultSize: {width: number; height: number | 'auto'};
}

export interface EnvironmentPreset {
  id: EnvironmentId;
  name: string;
  subtitle: string;
  icon: string;
  horizonColor: number;
  accentHex: string;
}

export interface WindowPose {
  x: number;
  y: number;
  z: number;
  rotY: number;
}

export interface WindowState {
  id: AppId;
  slotIndex: number;
  pinnedToHead: boolean;
  focusedAt: number;
}

export interface NoteItem {
  id: string;
  title: string;
  body: string;
  done: boolean;
}

export interface TrackInfo {
  id: string;
  title: string;
  artist: string;
  bpm: number;
  rootHz: number;
  chordIntervals: readonly number[];
  palette: readonly [string, string, string];
}

export interface SystemSettings {
  theme: ThemePreset;
  soundEnabled: boolean;
  handRaysVisible: boolean;
  wifiConnected: boolean;
  bluetoothConnected: boolean;
  doNotDisturb: boolean;
  immersion: number;
  environment: EnvironmentId;
}

export interface LauncherSnapshot {
  activeTab: LauncherTab;
  activeCategory: 'all' | AppCategory;
  homeVisible: boolean;
  controlCenterVisible: boolean;
  installedApps: AppId[];
  pinnedDockApps: AppId[];
  openWindows: WindowState[];
  settings: SystemSettings;
}
