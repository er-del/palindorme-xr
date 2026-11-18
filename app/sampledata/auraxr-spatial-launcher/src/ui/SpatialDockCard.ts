import * as xb from 'xrblocks';

import {
  getAppDefinition,
  type LauncherStateStore,
} from '../launcher/launcherState';
import {type SoundEngine} from '../launcher/soundEngine';
import {type AppId, type LauncherSnapshot} from '../launcher/types';

export interface SpatialDockCallbacks {
  onLaunchApp: (id: AppId) => void;
}

/**
 * Ergonomic floating Spatial Dock positioned slightly below eye level,
 * holding pinned Android apps, Home toggle, Control Center toggle, and live clock.
 */
export class SpatialDockCard extends xb.UICard {
  private store: LauncherStateStore;
  private sound: SoundEngine;
  private callbacks: SpatialDockCallbacks;

  private homeButton: xb.UIButton;
  private pinnedRow: xb.UIPanel;
  private controlCenterButton: xb.UIButton;
  private clockText: xb.UIText;
  private lastSignature = '';
  private lastClockStr = '';

  constructor(
    store: LauncherStateStore,
    sound: SoundEngine,
    callbacks: SpatialDockCallbacks
  ) {
    const homeButton = new xb.UIButton({
      ariaLabel: 'Toggle Home Launcher',
      onClick: () => {
        sound.playTap();
        store.toggleHome();
      },
      style: {
        width: 46,
        height: 46,
        borderRadius: 23,
        paddingLeft: 0,
        paddingRight: 0,
        justifyContent: 'center',
        alignItems: 'center',
        backgroundColor: 'rgba(59, 130, 246, 0.55)',
      },
      children: [
        new xb.UIIcon({
          icon: 'home',
          pointerEvents: 'none',
          style: {width: 24, height: 24, color: '#ffffff'},
        }),
      ],
    });

    const pinnedRow = new xb.UIPanel({
      style: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 10,
      },
    });

    const controlCenterButton = new xb.UIButton({
      ariaLabel: 'Toggle Control Center',
      onClick: () => {
        sound.playTap();
        store.toggleControlCenter();
      },
      style: {
        width: 46,
        height: 46,
        borderRadius: 23,
        paddingLeft: 0,
        paddingRight: 0,
        justifyContent: 'center',
        alignItems: 'center',
        backgroundColor: 'rgba(255, 255, 255, 0.1)',
      },
      children: [
        new xb.UIIcon({
          icon: 'tune',
          pointerEvents: 'none',
          style: {width: 22, height: 22, color: '#ffffff'},
        }),
      ],
    });

    const clockText = new xb.UIText({
      text: '12:00',
      style: {fontSize: 15, fontWeight: 'bold', paddingLeft: 6, paddingRight: 4},
    });

    super({
      size: {width: 0.72, height: 'auto'},
      manipulation: true,
      style: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 10,
        paddingTop: 12,
        paddingBottom: 12,
        paddingLeft: 16,
        paddingRight: 16,
        borderRadius: 36,
      },
      children: [
        homeButton,
        pinnedRow,
        new xb.UIPanel({
          style: {flexDirection: 'row', alignItems: 'center', gap: 10},
          children: [controlCenterButton, clockText],
        }),
      ],
    });

    this.name = 'AuraXR Spatial Dock';
    this.store = store;
    this.sound = sound;
    this.callbacks = callbacks;
    this.homeButton = homeButton;
    this.pinnedRow = pinnedRow;
    this.controlCenterButton = controlCenterButton;
    this.clockText = clockText;

    this.resetPose();
    this.sync(store.getSnapshot());
    this.tick();
  }

  tick(): void {
    const str = new Date().toLocaleTimeString([], {
      hour: '2-digit',
      minute: '2-digit',
    });
    if (str !== this.lastClockStr) {
      this.lastClockStr = str;
      this.clockText.text = str;
    }
  }

  resetPose(): void {
    this.position.set(0, 1.04, -1.04);
    this.rotation.set(-0.22, 0, 0);
    this.scale.setScalar(1);
  }

  sync(snapshot: LauncherSnapshot): void {
    this.homeButton.style.backgroundColor = snapshot.homeVisible
      ? 'rgba(59, 130, 246, 0.75)'
      : 'rgba(255, 255, 255, 0.1)';

    this.controlCenterButton.style.backgroundColor =
      snapshot.controlCenterVisible
        ? 'rgba(59, 130, 246, 0.75)'
        : 'rgba(255, 255, 255, 0.1)';

    const sig = JSON.stringify({
      pinned: snapshot.pinnedDockApps,
      open: snapshot.openWindows.map((w) => w.id),
    });
    if (sig !== this.lastSignature) {
      this.lastSignature = sig;
      this.rebuildPinnedShortcuts(snapshot);
    }
  }

  private rebuildPinnedShortcuts(snapshot: LauncherSnapshot): void {
    this.pinnedRow.remove(...this.pinnedRow.children);
    const openSet = new Set(snapshot.openWindows.map((w) => w.id));

    snapshot.pinnedDockApps.forEach((appId) => {
      const def = getAppDefinition(appId);
      const isOpen = openSet.has(appId);
      const btn = new xb.UIButton({
        ariaLabel: `Dock launch ${def.name}`,
        onClick: () => {
          this.sound.playOpen();
          this.callbacks.onLaunchApp(appId);
        },
        style: {
          width: 46,
          height: 46,
          borderRadius: 23,
          paddingLeft: 0,
          paddingRight: 0,
          justifyContent: 'center',
          alignItems: 'center',
          backgroundColor: def.accentColor,
          borderWidth: isOpen ? 2.5 : 1,
          borderColor: isOpen ? '#ffffff' : 'rgba(255, 255, 255, 0.35)',
        },
        children: [
          new xb.UIIcon({
            icon: def.icon,
            pointerEvents: 'none',
            style: {width: 24, height: 24, color: '#ffffff'},
          }),
        ],
      });
      this.pinnedRow.add(btn);
    });
  }
}
