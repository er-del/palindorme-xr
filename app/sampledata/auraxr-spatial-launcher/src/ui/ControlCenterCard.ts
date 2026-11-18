import * as xb from 'xrblocks';

import {
  ENVIRONMENT_PRESETS,
  type LauncherStateStore,
} from '../launcher/launcherState';
import {type SoundEngine} from '../launcher/soundEngine';
import {type LauncherSnapshot} from '../launcher/types';

export interface ControlCenterCallbacks {
  onRecenter: () => void;
}

/**
 * Quick system Control Center floating card for Wi-Fi, Bluetooth, DND,
 * Sound FX, Environment cycling, Immersion dial, and workspace recentering.
 */
export class ControlCenterCard extends xb.UICard {
  private wifiBtn: xb.UIButton;
  private btBtn: xb.UIButton;
  private dndBtn: xb.UIButton;
  private soundBtn: xb.UIButton;
  private immersionSlider: xb.UISlider;
  private immersionLabel: xb.UIText;
  private envCycleBtn: xb.UIButton;

  constructor(
    store: LauncherStateStore,
    sound: SoundEngine,
    callbacks: ControlCenterCallbacks
  ) {
    const wifiBtn = new xb.UIButton({
      label: 'Wi-Fi',
      icon: 'wifi',
      onClick: () => {
        sound.playTap();
        store.toggleSetting('wifiConnected');
      },
      style: {flexGrow: 1, height: 42, borderRadius: 18, fontSize: 13},
    });

    const btBtn = new xb.UIButton({
      label: 'Bluetooth',
      icon: 'bluetooth',
      onClick: () => {
        sound.playTap();
        store.toggleSetting('bluetoothConnected');
      },
      style: {flexGrow: 1, height: 42, borderRadius: 18, fontSize: 13},
    });

    const dndBtn = new xb.UIButton({
      label: 'Focus',
      icon: 'do_not_disturb_on',
      onClick: () => {
        sound.playTap();
        store.toggleSetting('doNotDisturb');
      },
      style: {flexGrow: 1, height: 42, borderRadius: 18, fontSize: 13},
    });

    const soundBtn = new xb.UIButton({
      label: 'Audio',
      icon: 'volume_up',
      onClick: () => {
        const next = store.toggleSetting('soundEnabled');
        sound.setEnabled(next);
        if (next) sound.playTap();
      },
      style: {flexGrow: 1, height: 42, borderRadius: 18, fontSize: 13},
    });

    const immersionLabel = new xb.UIText({
      text: '0%',
      style: {fontSize: 14, fontWeight: 'bold'},
    });

    const immersionSlider = new xb.UISlider({
      ariaLabel: 'Control center immersion dial',
      min: 0,
      max: 100,
      step: 5,
      value: 0,
      style: {width: '100%', height: 38},
      onInput: (val) => {
        store.setImmersion(val / 100);
      },
    });

    const envCycleBtn = new xb.UIButton({
      label: 'Environment: Passthrough',
      icon: 'landscape',
      onClick: () => {
        sound.playEnvironmentShift();
        const curr = store.getSnapshot().settings.environment;
        const idx = ENVIRONMENT_PRESETS.findIndex((e) => e.id === curr);
        const next =
          ENVIRONMENT_PRESETS[(idx + 1) % ENVIRONMENT_PRESETS.length];
        store.setEnvironment(next.id);
      },
      style: {width: '100%', height: 40, borderRadius: 18, fontSize: 13},
    });

    const header = new xb.UIPanel({
      style: {
        width: '100%',
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
      },
      children: [
        new xb.UIText({
          text: 'Control Center',
          style: {fontSize: 20, fontWeight: 'bold'},
        }),
        new xb.UIButton({
          ariaLabel: 'Close Control Center',
          onClick: () => {
            sound.playTap();
            store.toggleControlCenter(false);
          },
          style: {
            width: 34,
            height: 34,
            borderRadius: 17,
            paddingLeft: 0,
            paddingRight: 0,
            justifyContent: 'center',
            alignItems: 'center',
          },
          children: [
            new xb.UIIcon({
              icon: 'close',
              pointerEvents: 'none',
              style: {width: 18, height: 18, color: '#ffffff'},
            }),
          ],
        }),
      ],
    });

    super({
      size: {width: 0.44, height: 'auto'},
      manipulation: true,
      edge: true,
      style: {
        flexDirection: 'column',
        gap: 12,
        padding: 18,
      },
      children: [
        header,
        new xb.UIPanel({
          style: {width: '100%', flexDirection: 'row', gap: 8},
          children: [wifiBtn, btBtn],
        }),
        new xb.UIPanel({
          style: {width: '100%', flexDirection: 'row', gap: 8},
          children: [dndBtn, soundBtn],
        }),
        new xb.UIPanel({
          style: {
            width: '100%',
            flexDirection: 'column',
            gap: 6,
            padding: 12,
            borderRadius: 16,
            backgroundColor: 'rgba(255, 255, 255, 0.06)',
          },
          children: [
            new xb.UIPanel({
              style: {
                width: '100%',
                flexDirection: 'row',
                justifyContent: 'space-between',
              },
              children: [
                new xb.UIText({text: 'VR Immersion', style: {fontSize: 14}}),
                immersionLabel,
              ],
            }),
            immersionSlider,
          ],
        }),
        envCycleBtn,
        new xb.UIButton({
          label: 'Recenter Workspace',
          icon: 'center_focus_strong',
          onClick: () => {
            sound.playTap();
            callbacks.onRecenter();
          },
          style: {width: '100%', height: 40, borderRadius: 18, fontSize: 13},
        }),
      ],
    });

    this.name = 'AuraXR Control Center';
    this.wifiBtn = wifiBtn;
    this.btBtn = btBtn;
    this.dndBtn = dndBtn;
    this.soundBtn = soundBtn;
    this.immersionSlider = immersionSlider;
    this.immersionLabel = immersionLabel;
    this.envCycleBtn = envCycleBtn;

    this.resetPose();
    this.sync(store.getSnapshot());
  }

  resetPose(): void {
    this.position.set(0.32, 1.32, -0.96);
    this.rotation.set(0, -0.24, 0);
    this.scale.setScalar(1);
  }

  sync(snapshot: LauncherSnapshot): void {
    this.visible = snapshot.controlCenterVisible;
    const activeColor = 'rgba(59, 130, 246, 0.65)';
    const idleColor = 'rgba(255, 255, 255, 0.08)';

    this.wifiBtn.style.backgroundColor = snapshot.settings.wifiConnected
      ? activeColor
      : idleColor;
    this.btBtn.style.backgroundColor = snapshot.settings.bluetoothConnected
      ? activeColor
      : idleColor;
    this.dndBtn.style.backgroundColor = snapshot.settings.doNotDisturb
      ? 'rgba(139, 92, 246, 0.65)'
      : idleColor;
    this.soundBtn.style.backgroundColor = snapshot.settings.soundEnabled
      ? activeColor
      : idleColor;
    this.soundBtn.icon = snapshot.settings.soundEnabled
      ? 'volume_up'
      : 'volume_off';

    const pct = Math.round(snapshot.settings.immersion * 100);
    this.immersionSlider.value = pct;
    this.immersionLabel.text = `${pct}%`;

    const env = ENVIRONMENT_PRESETS.find(
      (e) => e.id === snapshot.settings.environment
    );
    this.envCycleBtn.label = `Environment: ${env?.name ?? 'Passthrough'}`;
  }
}
