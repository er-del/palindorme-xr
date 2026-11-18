import * as xb from 'xrblocks';

import {APP_CATALOG, type LauncherStateStore} from '../launcher/launcherState';
import {type SoundEngine} from '../launcher/soundEngine';
import {type AppDefinition, type AppId, type WindowPose} from '../launcher/types';
import {SpatialWindow, type SpatialWindowCallbacks} from './SpatialWindow';

export function createStoreWindow(
  app: AppDefinition,
  pose: WindowPose,
  callbacks: SpatialWindowCallbacks,
  store: LauncherStateStore,
  sound: SoundEngine,
  onOpenApp: (id: AppId) => void
): SpatialWindow {
  const listView = new xb.UIScrollView({
    ariaLabel: 'Android Spatial App Store',
    style: {flexGrow: 1, flexBasis: 0, minHeight: 190, gap: 10},
  });

  const renderStore = () => {
    listView.remove(...listView.children);
    const snap = store.getSnapshot();
    const installed = new Set(snap.installedApps);

    APP_CATALOG.filter((item) => item.id !== 'store').forEach((item) => {
      const isInstalled = installed.has(item.id);
      const isPinned = snap.pinnedDockApps.includes(item.id);

      const actionBtn = new xb.UIButton({
        label: isInstalled ? 'Open' : 'Install',
        onClick: () => {
          if (isInstalled) {
            sound.playOpen();
            onOpenApp(item.id);
          } else {
            sound.playOpen();
            store.installApp(item.id);
          }
        },
        style: {
          height: 34,
          borderRadius: 17,
          fontSize: 13,
          backgroundColor: isInstalled
            ? 'rgba(59, 130, 246, 0.55)'
            : '#10b981',
        },
      });

      const secondaryBtn = !item.installedByDefault && isInstalled
        ? new xb.UIButton({
            icon: 'delete',
            ariaLabel: `Uninstall ${item.name}`,
            onClick: () => {
              sound.playClose();
              store.uninstallApp(item.id);
            },
            style: {
              width: 34,
              height: 34,
              borderRadius: 17,
              paddingLeft: 0,
              paddingRight: 0,
              backgroundColor: 'rgba(239, 68, 68, 0.35)',
            },
          })
        : new xb.UIButton({
            icon: 'keep',
            ariaLabel: `${isPinned ? 'Unpin' : 'Pin'} ${item.name} on dock`,
            onClick: () => {
              sound.playTap();
              store.togglePinToDock(item.id);
            },
            style: {
              width: 34,
              height: 34,
              borderRadius: 17,
              paddingLeft: 0,
              paddingRight: 0,
              backgroundColor: isPinned
                ? 'rgba(16, 185, 129, 0.5)'
                : 'rgba(255, 255, 255, 0.08)',
            },
          });

      const row = new xb.UIPanel({
        style: {
          width: '100%',
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: 12,
          borderRadius: 14,
          backgroundColor: 'rgba(255, 255, 255, 0.07)',
          gap: 10,
        },
        children: [
          new xb.UIPanel({
            style: {flexDirection: 'row', alignItems: 'center', gap: 10, flexGrow: 1},
            children: [
              new xb.UIPanel({
                style: {
                  width: 36,
                  height: 36,
                  borderRadius: 18,
                  backgroundColor: item.accentColor,
                  justifyContent: 'center',
                  alignItems: 'center',
                },
                children: [
                  new xb.UIIcon({
                    icon: item.icon,
                    pointerEvents: 'none',
                    style: {width: 20, height: 20, color: '#ffffff'},
                  }),
                ],
              }),
              new xb.UIPanel({
                style: {flexDirection: 'column', alignItems: 'flex-start', gap: 2},
                children: [
                  new xb.UIText({
                    text: item.name,
                    style: {fontSize: 15, fontWeight: 'bold'},
                  }),
                  new xb.UIText({
                    text: item.subtitle,
                    style: {fontSize: 12, opacity: 0.7},
                  }),
                ],
              }),
            ],
          }),
          new xb.UIPanel({
            style: {flexDirection: 'row', gap: 6, alignItems: 'center'},
            children: [secondaryBtn, actionBtn],
          }),
        ],
      });
      listView.add(row);
    });
  };

  renderStore();
  const unsubscribe = store.subscribe(() => renderStore());

  const headerText = new xb.UIText({
    text: 'Install optional Android XR spatial utilities or pin favorites to your Dock.',
    style: {fontSize: 13, opacity: 0.78, flexShrink: 0},
  });

  const win = new SpatialWindow(app, pose, callbacks, [headerText, listView]);
  win.registerCleanup(unsubscribe);
  return win;
}
