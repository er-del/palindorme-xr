import * as xb from 'xrblocks';

import {createAppWindow} from '../apps/AppWindowFactory';
import {type SpatialWindow} from '../apps/SpatialWindow';
import {SpatialEnvironments} from '../environments/SpatialEnvironments';
import {
  computeWindowArcPose,
  LauncherStateStore,
} from './launcherState';
import {SoundEngine} from './soundEngine';
import {type AppId, type LauncherSnapshot, type ThemePreset} from './types';
import {ControlCenterCard} from '../ui/ControlCenterCard';
import {LauncherHomeCard} from '../ui/LauncherHomeCard';
import {SpatialDockCard} from '../ui/SpatialDockCard';

declare global {
  interface Window {
    __AURAXR__?: {
      getState: () => LauncherSnapshot;
      openApp: (id: AppId) => void;
      closeApp: (id: AppId) => void;
      setEnvironment: (
        env: 'passthrough' | 'alpine' | 'lunar' | 'obsidian'
      ) => void;
      setImmersion: (val: number) => void;
      setTab: (tab: 'apps' | 'environments' | 'widgets' | 'settings') => void;
      toggleControlCenter: (visible?: boolean) => void;
      setTheme: (theme: 'grayGlass' | 'glimmer' | 'highContrast') => void;
      validateUI: () => unknown;
    };
  }
}

/**
 * Root orchestrator Script for AuraXR Spatial Launcher.
 * Coordinates LauncherStateStore, floating SpatialWindows, Home Launcher,
 * Dock, Control Center, SoundEngine, and 3D SpatialEnvironments.
 */
export class VisionLauncherScript extends xb.Script {
  private store = new LauncherStateStore();
  private sound = new SoundEngine();
  private environments = new SpatialEnvironments();

  private homeCard!: LauncherHomeCard;
  private dockCard!: SpatialDockCard;
  private controlCenterCard!: ControlCenterCard;
  private windows = new Map<AppId, SpatialWindow>();
  private unsubscribe?: () => void;
  private appliedTheme?: ThemePreset;

  init(): void {
    this.add(this.environments);

    this.homeCard = new LauncherHomeCard(this.store, this.sound, {
      onLaunchApp: (id) => this.launchApp(id),
      onRecenter: () => this.recenterWorkspace(),
    });
    this.dockCard = new SpatialDockCard(this.store, this.sound, {
      onLaunchApp: (id) => this.launchApp(id),
    });
    this.controlCenterCard = new ControlCenterCard(this.store, this.sound, {
      onRecenter: () => this.recenterWorkspace(),
    });

    this.add(this.homeCard, this.dockCard, this.controlCenterCard);

    this.unsubscribe = this.store.subscribe((snapshot) =>
      this.onStateChanged(snapshot)
    );
    this.onStateChanged(this.store.getSnapshot());
    this.exposeVerificationHooks();
  }

  update(): void {
    const dt = xb.getDeltaTime() || 0.016;
    this.dockCard?.tick();
    for (const win of this.windows.values()) {
      win.tick(dt);
    }
  }

  private launchApp(id: AppId): void {
    this.store.openApp(id);
  }

  private recenterWorkspace(): void {
    this.homeCard.resetPose();
    this.dockCard.resetPose();
    this.controlCenterCard.resetPose();
    const snap = this.store.getSnapshot();
    snap.openWindows.forEach((winState) => {
      const win = this.windows.get(winState.id);
      if (win) {
        win.setPose(computeWindowArcPose(winState.slotIndex));
      }
    });
  }

  private onStateChanged(snapshot: LauncherSnapshot): void {
    // Sync theme
    if (this.appliedTheme !== snapshot.settings.theme) {
      this.appliedTheme = snapshot.settings.theme;
      xb.ui.theme = snapshot.settings.theme;
    }

    // Sync sound & pointer rays
    this.sound.setEnabled(snapshot.settings.soundEnabled);
    if (xb.core.input) {
      xb.core.input.controllers.forEach((ctrl) => {
        const line = ctrl.getObjectByName('ray');
        if (line) line.visible = snapshot.settings.handRaysVisible;
      });
    }

    // Sync 3D environment & XRTransition
    this.environments.setTarget(
      snapshot.settings.environment,
      snapshot.settings.immersion
    );

    // Reconcile open 3D windows
    const desiredIds = new Set(snapshot.openWindows.map((w) => w.id));
    for (const [id, win] of this.windows.entries()) {
      if (!desiredIds.has(id)) {
        win.disposeWindow();
        this.windows.delete(id);
      }
    }

    for (const winState of snapshot.openWindows) {
      if (!this.windows.has(winState.id)) {
        const pose = computeWindowArcPose(winState.slotIndex);
        const win = createAppWindow(
          winState.id,
          pose,
          {
            onClose: () => {
              this.sound.playClose();
              this.store.closeApp(winState.id);
            },
            onTogglePin: () => {
              this.sound.playTap();
              return this.store.togglePinWindowToHead(winState.id) ?? false;
            },
          },
          this.store,
          this.sound,
          (appId) => this.launchApp(appId)
        );
        this.windows.set(winState.id, win);
        this.add(win, ...win.getAttachments());
      } else {
        const win = this.windows.get(winState.id)!;
        win.setPinnedToHead(winState.pinnedToHead);
      }
    }

    // Update shell cards
    this.homeCard.sync(snapshot);
    this.dockCard.sync(snapshot);
    this.controlCenterCard.sync(snapshot);

    // Expose semantic state attributes for browser verification
    if (typeof document !== 'undefined') {
      document.body.dataset.launcherReady = 'true';
      document.body.dataset.activeTab = snapshot.activeTab;
      document.body.dataset.environment = snapshot.settings.environment;
      document.body.dataset.immersion = String(snapshot.settings.immersion);
      document.body.dataset.openWindows = snapshot.openWindows
        .map((w) => w.id)
        .join(',');
      document.body.dataset.theme = snapshot.settings.theme;
      document.body.dataset.controlCenter = String(
        snapshot.controlCenterVisible
      );
      document.body.dataset.homeVisible = String(snapshot.homeVisible);
    }
  }

  private exposeVerificationHooks(): void {
    if (typeof window === 'undefined') return;
    window.__AURAXR__ = {
      getState: () => this.store.getSnapshot(),
      openApp: (id) => this.store.openApp(id),
      closeApp: (id) => this.store.closeApp(id),
      setEnvironment: (env) => this.store.setEnvironment(env),
      setImmersion: (val) => this.store.setImmersion(val),
      setTab: (tab) => this.store.setTab(tab),
      toggleControlCenter: (v) => this.store.toggleControlCenter(v),
      setTheme: (theme) => this.store.setTheme(theme),
      validateUI: () => xb.ui.validate(),
    };
  }

  dispose(): void {
    this.unsubscribe?.();
    this.sound.dispose();
    for (const win of this.windows.values()) {
      win.disposeWindow();
    }
    this.windows.clear();
  }
}
