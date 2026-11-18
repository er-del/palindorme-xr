import * as xb from 'xrblocks';

import {
  ENVIRONMENT_PRESETS,
  type LauncherStateStore,
} from '../launcher/launcherState';
import {type SoundEngine} from '../launcher/soundEngine';
import {
  type AppCategory,
  type AppDefinition,
  type AppId,
  type LauncherSnapshot,
  type LauncherTab,
  type ThemePreset,
} from '../launcher/types';

export interface LauncherHomeCallbacks {
  onLaunchApp: (id: AppId) => void;
  onRecenter: () => void;
}

/**
 * VisionOS-inspired floating Spatial Home Launcher (`xb.UICard`) with left
 * navigation rail, Android & Spatial App Grid, Environments + Immersion Dial,
 * Active Widgets manager, and System Settings.
 */
export class LauncherHomeCard extends xb.UICard {
  private store: LauncherStateStore;
  private sound: SoundEngine;
  private callbacks: LauncherHomeCallbacks;

  private navButtons: Record<LauncherTab, xb.UIButton>;
  private contentHeaderTitle: xb.UIText;
  private contentHeaderSub: xb.UIText;

  private appsView: xb.UIPanel;
  private categoryButtons: Record<'all' | AppCategory, xb.UIButton>;
  private appGridScroll: xb.UIScrollView;

  private environmentsView: xb.UIPanel;
  private envButtons: Record<string, xb.UIButton> = {};
  private immersionSlider: xb.UISlider;
  private immersionReadout: xb.UIText;

  private widgetsView: xb.UIPanel;
  private widgetsSummary: xb.UIText;
  private widgetsList: xb.UIScrollView;

  private settingsView: xb.UIPanel;
  private themeButtons: Record<ThemePreset, xb.UIButton>;
  private soundToggleBtn: xb.UIButton;
  private raysToggleBtn: xb.UIButton;

  private lastSignature = '';

  constructor(
    store: LauncherStateStore,
    sound: SoundEngine,
    callbacks: LauncherHomeCallbacks
  ) {
    const navSpecs: ReadonlyArray<{
      tab: LauncherTab;
      label: string;
      icon: string;
    }> = [
      {tab: 'apps', label: 'Apps', icon: 'apps'},
      {tab: 'environments', label: 'Environments', icon: 'landscape'},
      {tab: 'widgets', label: 'Windows', icon: 'view_carousel'},
      {tab: 'settings', label: 'Settings', icon: 'settings'},
    ];

    const navButtons = {} as Record<LauncherTab, xb.UIButton>;
    navSpecs.forEach(({tab, label, icon}) => {
      navButtons[tab] = new xb.UIButton({
        label,
        icon,
        onClick: () => {
          sound.playTap();
          store.setTab(tab);
        },
        style: {
          width: '100%',
          height: 44,
          borderRadius: 16,
          justifyContent: 'flex-start',
          paddingLeft: 14,
          paddingRight: 14,
          fontSize: 15,
        },
      });
    });

    const sidebar = new xb.UIPanel({
      style: {
        width: 184,
        height: '100%',
        flexDirection: 'column',
        justifyContent: 'space-between',
        padding: 14,
        borderRadius: 22,
        backgroundColor: 'rgba(255, 255, 255, 0.06)',
        gap: 12,
      },
      children: [
        new xb.UIPanel({
          style: {
            width: '100%',
            flexDirection: 'column',
            alignItems: 'flex-start',
            gap: 10,
          },
          children: [
            new xb.UIPanel({
              style: {
                flexDirection: 'column',
                alignItems: 'flex-start',
                gap: 2,
                paddingLeft: 6,
                paddingBottom: 6,
              },
              children: [
                new xb.UIText({
                  text: 'AuraXR',
                  style: {fontSize: 24, fontWeight: 'bold'},
                }),
                new xb.UIText({
                  text: 'Android Spatial OS',
                  style: {fontSize: 12, opacity: 0.65},
                }),
              ],
            }),
            ...navSpecs.map((s) => navButtons[s.tab]),
          ],
        }),
        new xb.UIButton({
          label: 'Hide Home',
          icon: 'visibility_off',
          onClick: () => {
            sound.playTap();
            store.toggleHome(false);
          },
          style: {
            width: '100%',
            height: 38,
            borderRadius: 16,
            fontSize: 13,
            backgroundColor: 'rgba(255, 255, 255, 0.06)',
          },
        }),
      ],
    });

    const contentHeaderTitle = new xb.UIText({
      text: 'Apps',
      style: {fontSize: 24, fontWeight: 'bold'},
    });
    const contentHeaderSub = new xb.UIText({
      text: 'Launch Android & Spatial experiences into your space',
      style: {fontSize: 13, opacity: 0.72},
    });

    // 1. APPS TAB
    const categories: ReadonlyArray<{id: 'all' | AppCategory; label: string}> = [
      {id: 'all', label: 'All'},
      {id: 'system', label: 'System'},
      {id: 'media', label: 'Media'},
      {id: 'tools', label: 'Tools'},
    ];
    const categoryButtons = {} as Record<'all' | AppCategory, xb.UIButton>;
    categories.forEach((cat) => {
      categoryButtons[cat.id] = new xb.UIButton({
        label: cat.label,
        onClick: () => {
          sound.playTap();
          store.setCategory(cat.id);
        },
        style: {
          height: 34,
          borderRadius: 17,
          paddingLeft: 16,
          paddingRight: 16,
          fontSize: 13,
        },
      });
    });

    const appGridScroll = new xb.UIScrollView({
      ariaLabel: 'Spatial apps grid',
      style: {width: '100%', flexGrow: 1, flexBasis: 0, minHeight: 140, gap: 14},
    });

    const appsView = new xb.UIPanel({
      style: {
        width: '100%',
        flexGrow: 1,
        flexDirection: 'column',
        gap: 12,
        display: 'flex',
      },
      children: [
        new xb.UIPanel({
          style: {width: '100%', flexDirection: 'row', gap: 8, flexShrink: 0},
          children: categories.map((c) => categoryButtons[c.id]),
        }),
        appGridScroll,
      ],
    });

    // 2. ENVIRONMENTS TAB
    const envButtons: Record<string, xb.UIButton> = {};
    const envGridRows: xb.UIPanel[] = [];
    for (let i = 0; i < ENVIRONMENT_PRESETS.length; i += 2) {
      const pair = ENVIRONMENT_PRESETS.slice(i, i + 2);
      const row = new xb.UIPanel({
        style: {width: '100%', flexDirection: 'row', gap: 12},
        children: pair.map((env) => {
          const btn = new xb.UIButton({
            ariaLabel: `Select ${env.name} environment`,
            onClick: () => {
              sound.playEnvironmentShift();
              store.setEnvironment(env.id);
            },
            style: {
              flexGrow: 1,
              flexBasis: 0,
              height: 88,
              borderRadius: 20,
              padding: 14,
              flexDirection: 'row',
              justifyContent: 'flex-start',
              alignItems: 'center',
              gap: 12,
              backgroundColor: 'rgba(255, 255, 255, 0.08)',
            },
            children: [
              new xb.UIPanel({
                pointerEvents: 'none',
                style: {
                  width: 44,
                  height: 44,
                  borderRadius: 22,
                  backgroundColor: env.accentHex,
                  justifyContent: 'center',
                  alignItems: 'center',
                },
                children: [
                  new xb.UIIcon({
                    icon: env.icon,
                    pointerEvents: 'none',
                    style: {width: 24, height: 24, color: '#ffffff'},
                  }),
                ],
              }),
              new xb.UIPanel({
                pointerEvents: 'none',
                style: {
                  flexDirection: 'column',
                  alignItems: 'flex-start',
                  gap: 2,
                },
                children: [
                  new xb.UIText({
                    text: env.name,
                    pointerEvents: 'none',
                    style: {fontSize: 16, fontWeight: 'bold'},
                  }),
                  new xb.UIText({
                    text: env.subtitle,
                    pointerEvents: 'none',
                    style: {fontSize: 12, opacity: 0.72},
                  }),
                ],
              }),
            ],
          });
          envButtons[env.id] = btn;
          return btn;
        }),
      });
      envGridRows.push(row);
    }

    const immersionReadout = new xb.UIText({
      text: '0% (Passthrough)',
      style: {fontSize: 15, fontWeight: 'bold'},
    });
    const immersionSlider = new xb.UISlider({
      ariaLabel: 'Environment immersion level',
      min: 0,
      max: 100,
      step: 5,
      value: 0,
      style: {width: '100%', height: 42},
      onInput: (val) => {
        store.setImmersion(val / 100);
      },
    });

    const environmentsView = new xb.UIPanel({
      visible: false,
      style: {
        display: 'none',
        width: '100%',
        flexGrow: 1,
        flexDirection: 'column',
        gap: 12,
      },
      children: [
        ...envGridRows,
        new xb.UIPanel({
          style: {
            width: '100%',
            flexDirection: 'column',
            gap: 8,
            padding: 14,
            borderRadius: 18,
            backgroundColor: 'rgba(255, 255, 255, 0.06)',
          },
          children: [
            new xb.UIPanel({
              style: {
                width: '100%',
                flexDirection: 'row',
                justifyContent: 'space-between',
                alignItems: 'center',
              },
              children: [
                new xb.UIText({
                  text: 'Immersion Dial',
                  style: {fontSize: 15, fontWeight: 'bold'},
                }),
                immersionReadout,
              ],
            }),
            immersionSlider,
          ],
        }),
      ],
    });

    // 3. WIDGETS / WINDOWS TAB
    const widgetsSummary = new xb.UIText({
      text: '0 active windows',
      style: {fontSize: 14, opacity: 0.78},
    });
    const widgetsList = new xb.UIScrollView({
      ariaLabel: 'Active spatial windows',
      style: {flexGrow: 1, flexBasis: 0, minHeight: 170, gap: 10},
    });

    const widgetsView = new xb.UIPanel({
      visible: false,
      style: {
        display: 'none',
        width: '100%',
        flexGrow: 1,
        flexDirection: 'column',
        gap: 12,
      },
      children: [
        new xb.UIPanel({
          style: {
            width: '100%',
            flexDirection: 'row',
            justifyContent: 'space-between',
            alignItems: 'center',
            gap: 8,
          },
          children: [
            widgetsSummary,
            new xb.UIPanel({
              style: {flexDirection: 'row', gap: 8},
              children: [
                new xb.UIButton({
                  label: 'Recenter',
                  icon: 'center_focus_strong',
                  onClick: () => {
                    sound.playTap();
                    callbacks.onRecenter();
                  },
                  style: {height: 36, borderRadius: 18, fontSize: 13},
                }),
                new xb.UIButton({
                  label: 'Close All',
                  icon: 'layers_clear',
                  onClick: () => {
                    sound.playClose();
                    store.closeAllApps();
                  },
                  style: {height: 36, borderRadius: 18, fontSize: 13},
                }),
              ],
            }),
          ],
        }),
        widgetsList,
      ],
    });

    // 4. SETTINGS TAB
    const themeOptions: ReadonlyArray<{id: ThemePreset; label: string}> = [
      {id: 'grayGlass', label: 'Gray Glass'},
      {id: 'glimmer', label: 'Glimmer'},
      {id: 'highContrast', label: 'High Contrast'},
    ];
    const themeButtons = {} as Record<ThemePreset, xb.UIButton>;
    themeOptions.forEach((opt) => {
      themeButtons[opt.id] = new xb.UIButton({
        label: opt.label,
        onClick: () => {
          sound.playTap();
          store.setTheme(opt.id);
        },
        style: {flexGrow: 1, flexBasis: 0, height: 40, borderRadius: 18, fontSize: 13},
      });
    });

    const soundToggleBtn = new xb.UIButton({
      label: 'Spatial Sound FX: On',
      icon: 'volume_up',
      onClick: () => {
        const next = store.toggleSetting('soundEnabled');
        sound.setEnabled(next);
        if (next) sound.playTap();
      },
      style: {width: '100%', height: 42, borderRadius: 18, justifyContent: 'flex-start'},
    });

    const raysToggleBtn = new xb.UIButton({
      label: 'Pointer Rays: Hidden',
      icon: 'stylus',
      onClick: () => {
        sound.playTap();
        store.toggleSetting('handRaysVisible');
      },
      style: {width: '100%', height: 42, borderRadius: 18, justifyContent: 'flex-start'},
    });

    const settingsView = new xb.UIPanel({
      visible: false,
      style: {
        display: 'none',
        width: '100%',
        flexGrow: 1,
        flexDirection: 'column',
        gap: 14,
      },
      children: [
        new xb.UIText({
          text: 'Spatial Glass Appearance',
          style: {fontSize: 15, fontWeight: 'bold'},
        }),
        new xb.UIPanel({
          style: {width: '100%', flexDirection: 'row', gap: 8},
          children: themeOptions.map((o) => themeButtons[o.id]),
        }),
        soundToggleBtn,
        raysToggleBtn,
        new xb.UIButton({
          label: 'Reset Spatial Workspace',
          icon: 'restart_alt',
          onClick: () => {
            sound.playClose();
            store.resetWorkspace();
            callbacks.onRecenter();
          },
          style: {
            width: '100%',
            height: 42,
            borderRadius: 18,
            backgroundColor: 'rgba(239, 68, 68, 0.3)',
          },
        }),
      ],
    });

    const rightArea = new xb.UIPanel({
      style: {
        flexGrow: 1,
        height: '100%',
        flexDirection: 'column',
        gap: 14,
        padding: 4,
      },
      children: [
        new xb.UIPanel({
          style: {
            width: '100%',
            flexDirection: 'column',
            alignItems: 'flex-start',
            gap: 2,
            flexShrink: 0,
          },
          children: [contentHeaderTitle, contentHeaderSub],
        }),
        appsView,
        environmentsView,
        widgetsView,
        settingsView,
      ],
    });

    super({
      size: {width: 0.96, height: 0.58},
      manipulation: true,
      edge: true,
      style: {
        flexDirection: 'row',
        gap: 18,
        padding: 20,
      },
      children: [sidebar, rightArea],
    });

    this.name = 'AuraXR Home Launcher';
    this.store = store;
    this.sound = sound;
    this.callbacks = callbacks;
    this.navButtons = navButtons;
    this.contentHeaderTitle = contentHeaderTitle;
    this.contentHeaderSub = contentHeaderSub;
    this.appsView = appsView;
    this.categoryButtons = categoryButtons;
    this.appGridScroll = appGridScroll;
    this.environmentsView = environmentsView;
    this.envButtons = envButtons;
    this.immersionSlider = immersionSlider;
    this.immersionReadout = immersionReadout;
    this.widgetsView = widgetsView;
    this.widgetsSummary = widgetsSummary;
    this.widgetsList = widgetsList;
    this.settingsView = settingsView;
    this.themeButtons = themeButtons;
    this.soundToggleBtn = soundToggleBtn;
    this.raysToggleBtn = raysToggleBtn;

    this.resetPose();
    this.sync(store.getSnapshot());
  }

  resetPose(): void {
    this.position.set(0, 1.48, -1.32);
    this.rotation.set(0, 0, 0);
    this.scale.setScalar(1);
  }

  sync(snapshot: LauncherSnapshot): void {
    this.visible = snapshot.homeVisible;

    (Object.keys(this.navButtons) as LauncherTab[]).forEach((tab) => {
      this.navButtons[tab].style.backgroundColor =
        tab === snapshot.activeTab
          ? 'rgba(59, 130, 246, 0.58)'
          : 'rgba(255, 255, 255, 0.06)';
    });

    const isApps = snapshot.activeTab === 'apps';
    const isEnvs = snapshot.activeTab === 'environments';
    const isWidgets = snapshot.activeTab === 'widgets';
    const isSettings = snapshot.activeTab === 'settings';

    this.appsView.visible = isApps;
    this.appsView.style.display = isApps ? 'flex' : 'none';
    this.environmentsView.visible = isEnvs;
    this.environmentsView.style.display = isEnvs ? 'flex' : 'none';
    this.widgetsView.visible = isWidgets;
    this.widgetsView.style.display = isWidgets ? 'flex' : 'none';
    this.settingsView.visible = isSettings;
    this.settingsView.style.display = isSettings ? 'flex' : 'none';

    if (snapshot.activeTab === 'apps') {
      this.contentHeaderTitle.text = 'Apps';
      this.contentHeaderSub.text =
        'Launch Android & Spatial experiences into your space';
    } else if (snapshot.activeTab === 'environments') {
      this.contentHeaderTitle.text = 'Environments';
      this.contentHeaderSub.text =
        'Blend real-world passthrough with immersive VR landscapes';
    } else if (snapshot.activeTab === 'widgets') {
      this.contentHeaderTitle.text = 'Windows';
      this.contentHeaderSub.text =
        'Manage floating windows, head-pinning, and workspace layout';
    } else {
      this.contentHeaderTitle.text = 'Settings';
      this.contentHeaderSub.text =
        'Configure glass material, spatial audio, and pointer rays';
    }

    (Object.keys(this.categoryButtons) as Array<'all' | AppCategory>).forEach(
      (cat) => {
        this.categoryButtons[cat].style.backgroundColor =
          cat === snapshot.activeCategory
            ? 'rgba(255, 255, 255, 0.24)'
            : 'rgba(255, 255, 255, 0.06)';
      }
    );

    const pct = Math.round(snapshot.settings.immersion * 100);
    this.immersionSlider.value = pct;
    this.immersionReadout.text =
      pct === 0 ? '0% (Passthrough)' : `${pct}% Immersion`;

    ENVIRONMENT_PRESETS.forEach((env) => {
      const active = env.id === snapshot.settings.environment;
      this.envButtons[env.id].style.backgroundColor = active
        ? 'rgba(59, 130, 246, 0.35)'
        : 'rgba(255, 255, 255, 0.08)';
      this.envButtons[env.id].style.borderColor = active
        ? '#60a5fa'
        : 'rgba(255, 255, 255, 0.16)';
    });

    (Object.keys(this.themeButtons) as ThemePreset[]).forEach((t) => {
      this.themeButtons[t].style.backgroundColor =
        t === snapshot.settings.theme
          ? 'rgba(59, 130, 246, 0.58)'
          : 'rgba(255, 255, 255, 0.08)';
    });

    this.soundToggleBtn.label = snapshot.settings.soundEnabled
      ? 'Spatial Sound FX: On'
      : 'Spatial Sound FX: Off';
    this.soundToggleBtn.icon = snapshot.settings.soundEnabled
      ? 'volume_up'
      : 'volume_off';

    this.raysToggleBtn.label = snapshot.settings.handRaysVisible
      ? 'Pointer Rays: Visible'
      : 'Pointer Rays: Hidden';

    const sig = JSON.stringify({
      cat: snapshot.activeCategory,
      installed: snapshot.installedApps,
      open: snapshot.openWindows,
    });
    if (sig !== this.lastSignature) {
      this.lastSignature = sig;
      this.rebuildAppGrid(snapshot);
      this.rebuildWindowsList(snapshot);
    }
  }

  private rebuildAppGrid(snapshot: LauncherSnapshot): void {
    this.appGridScroll.remove(...this.appGridScroll.children);
    const apps = this.store.getFilteredInstalledApps();
    const openIds = new Set(snapshot.openWindows.map((w) => w.id));

    for (let i = 0; i < apps.length; i += 4) {
      const rowApps = apps.slice(i, i + 4);
      const row = new xb.UIPanel({
        style: {
          width: '100%',
          flexDirection: 'row',
          justifyContent: 'flex-start',
          gap: 12,
        },
        children: rowApps.map((app) => this.createAppTile(app, openIds.has(app.id))),
      });
      this.appGridScroll.add(row);
    }
  }

  private createAppTile(app: AppDefinition, isOpen: boolean): xb.UIPanel {
    const iconButton = new xb.UIButton({
      ariaLabel: `Open ${app.name}`,
      onClick: () => {
        this.sound.playOpen();
        this.callbacks.onLaunchApp(app.id);
      },
      style: {
        width: 64,
        height: 64,
        borderRadius: 32,
        paddingLeft: 0,
        paddingRight: 0,
        justifyContent: 'center',
        alignItems: 'center',
        backgroundColor: app.accentColor,
        borderWidth: isOpen ? 2.5 : 1.5,
        borderColor: isOpen ? '#ffffff' : 'rgba(255, 255, 255, 0.35)',
      },
      children: [
        new xb.UIIcon({
          icon: app.icon,
          pointerEvents: 'none',
          style: {width: 30, height: 30, color: '#ffffff'},
        }),
      ],
    });

    return new xb.UIPanel({
      style: {
        width: 128,
        flexDirection: 'column',
        alignItems: 'center',
        gap: 6,
        padding: 4,
      },
      children: [
        iconButton,
        new xb.UIText({
          text: isOpen ? `${app.name} · Open` : app.name,
          style: {fontSize: 13, fontWeight: 'bold', textAlign: 'center'},
        }),
      ],
    });
  }

  private rebuildWindowsList(snapshot: LauncherSnapshot): void {
    this.widgetsList.remove(...this.widgetsList.children);
    const count = snapshot.openWindows.length;
    this.widgetsSummary.text =
      count === 1 ? '1 active window' : `${count} active windows`;

    if (count === 0) {
      this.widgetsList.add(
        new xb.UIText({
          text: 'No open spatial windows yet. Select any app from the Apps tab or Dock to launch it into space.',
          style: {fontSize: 14, opacity: 0.72, lineHeight: 1.4},
        })
      );
      return;
    }

    const installedApps = this.store.getFilteredInstalledApps();
    const byId = new Map(installedApps.map((a) => [a.id, a]));

    snapshot.openWindows.forEach((win) => {
      const def = byId.get(win.id);
      const name = def?.name ?? win.id;
      const row = new xb.UIPanel({
        style: {
          width: '100%',
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: 12,
          borderRadius: 16,
          backgroundColor: 'rgba(255, 255, 255, 0.08)',
        },
        children: [
          new xb.UIText({
            text: win.pinnedToHead ? `${name} · Pinned to View` : name,
            style: {fontSize: 15, fontWeight: 'bold'},
          }),
          new xb.UIPanel({
            style: {flexDirection: 'row', gap: 8},
            children: [
              new xb.UIButton({
                label: win.pinnedToHead ? 'Unpin' : 'Pin',
                onClick: () => {
                  this.sound.playTap();
                  this.store.togglePinWindowToHead(win.id);
                },
                style: {height: 34, borderRadius: 17, fontSize: 12},
              }),
              new xb.UIButton({
                label: 'Close',
                onClick: () => {
                  this.sound.playClose();
                  this.store.closeApp(win.id);
                },
                style: {
                  height: 34,
                  borderRadius: 17,
                  fontSize: 12,
                  backgroundColor: 'rgba(239, 68, 68, 0.45)',
                },
              }),
            ],
          }),
        ],
      });
      this.widgetsList.add(row);
    });
  }
}
