import * as xb from 'xrblocks';

import {type SoundEngine} from '../launcher/soundEngine';
import {type AppDefinition, type AppId, type WindowPose} from '../launcher/types';
import {SpatialWindow, type SpatialWindowCallbacks} from './SpatialWindow';

interface FileItem {
  name: string;
  size: string;
  type: string;
  icon: string;
  launchApp?: AppId;
}

const FOLDERS: Record<string, readonly FileItem[]> = {
  APKs: [
    {
      name: 'com.auraxr.browser.apk',
      size: '18.4 MB',
      type: 'Android Spatial APK',
      icon: 'android',
      launchApp: 'browser',
    },
    {
      name: 'com.auraxr.studio3d.apk',
      size: '24.1 MB',
      type: 'OpenXR Model Inspector',
      icon: 'view_in_ar',
      launchApp: 'studio3d',
    },
    {
      name: 'com.auraxr.adb_bridge.apk',
      size: '6.2 MB',
      type: 'Wireless ADB Utility',
      icon: 'terminal',
      launchApp: 'terminal',
    },
  ],
  Pictures: [
    {
      name: 'Haleakala_Spatial_01.heic',
      size: '9.8 MB',
      type: 'Stereo Panorama',
      icon: 'panorama',
      launchApp: 'photos',
    },
    {
      name: 'Shibuya_Depth_Capture.png',
      size: '5.4 MB',
      type: 'Depth Layered Image',
      icon: 'photo',
      launchApp: 'photos',
    },
  ],
  Documents: [
    {
      name: 'Spatial_Workspace_Notes.md',
      size: '14 KB',
      type: 'Markdown Note',
      icon: 'description',
      launchApp: 'notes',
    },
    {
      name: 'Android_XR_Manifest.json',
      size: '3.2 KB',
      type: 'Launcher Config',
      icon: 'data_object',
    },
  ],
};

export function createFilesWindow(
  app: AppDefinition,
  pose: WindowPose,
  callbacks: SpatialWindowCallbacks,
  sound: SoundEngine,
  onOpenApp: (id: AppId) => void
): SpatialWindow {
  let activeFolder = 'APKs';
  let selectedFile: FileItem = FOLDERS.APKs[0];

  const folderButtons: Record<string, xb.UIButton> = {};
  const listView = new xb.UIScrollView({
    ariaLabel: 'Android files list',
    style: {flexGrow: 1, flexBasis: 0, minHeight: 150, gap: 8},
  });

  const detailTitle = new xb.UIText({
    text: selectedFile.name,
    style: {fontSize: 15, fontWeight: 'bold'},
  });
  const detailMeta = new xb.UIText({
    text: `${selectedFile.type} · ${selectedFile.size}`,
    style: {fontSize: 13, opacity: 0.72},
  });
  const openTargetBtn = new xb.UIButton({
    label: 'Open App',
    icon: 'open_in_new',
    onClick: () => {
      if (selectedFile.launchApp) {
        sound.playOpen();
        onOpenApp(selectedFile.launchApp);
      }
    },
    style: {height: 34, borderRadius: 17, fontSize: 13},
  });

  const inspectorPanel = new xb.UIPanel({
    style: {
      width: '100%',
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      padding: 12,
      borderRadius: 14,
      backgroundColor: 'rgba(255, 255, 255, 0.08)',
      gap: 10,
    },
    children: [
      new xb.UIPanel({
        style: {flexDirection: 'column', alignItems: 'flex-start', gap: 2, flexGrow: 1},
        children: [detailTitle, detailMeta],
      }),
      openTargetBtn,
    ],
  });

  const updateInspector = (file: FileItem) => {
    selectedFile = file;
    detailTitle.text = file.name;
    detailMeta.text = `${file.type} · ${file.size}`;
    openTargetBtn.disabled = !file.launchApp;
  };

  const renderFiles = () => {
    listView.remove(...listView.children);
    const items = FOLDERS[activeFolder] ?? [];
    items.forEach((file) => {
      const btn = new xb.UIButton({
        ariaLabel: `Inspect ${file.name}`,
        onClick: () => {
          sound.playTap();
          updateInspector(file);
        },
        style: {
          width: '100%',
          height: 52,
          borderRadius: 14,
          paddingLeft: 14,
          paddingRight: 14,
          justifyContent: 'space-between',
          backgroundColor: 'rgba(255, 255, 255, 0.06)',
        },
        children: [
          new xb.UIPanel({
            style: {flexDirection: 'row', alignItems: 'center', gap: 10},
            pointerEvents: 'none',
            children: [
              new xb.UIIcon({
                icon: file.icon,
                pointerEvents: 'none',
                style: {width: 22, height: 22, color: '#38bdf8'},
              }),
              new xb.UIText({
                text: file.name,
                pointerEvents: 'none',
                style: {fontSize: 14, fontWeight: 'bold'},
              }),
            ],
          }),
          new xb.UIText({
            text: file.size,
            pointerEvents: 'none',
            style: {fontSize: 12, opacity: 0.68},
          }),
        ],
      });
      listView.add(btn);
    });

    Object.entries(folderButtons).forEach(([folder, btn]) => {
      btn.style.backgroundColor =
        folder === activeFolder
          ? 'rgba(14, 165, 233, 0.55)'
          : 'rgba(255, 255, 255, 0.08)';
    });
  };

  const folderTabs = new xb.UIPanel({
    style: {width: '100%', flexDirection: 'row', gap: 8},
    children: Object.keys(FOLDERS).map((folder) => {
      const btn = new xb.UIButton({
        label: folder,
        onClick: () => {
          sound.playTap();
          activeFolder = folder;
          updateInspector(FOLDERS[folder][0]);
          renderFiles();
        },
        style: {flexGrow: 1, height: 36, borderRadius: 18, fontSize: 13},
      });
      folderButtons[folder] = btn;
      return btn;
    }),
  });

  updateInspector(FOLDERS.APKs[0]);
  renderFiles();

  return new SpatialWindow(app, pose, callbacks, [
    folderTabs,
    listView,
    inspectorPanel,
  ]);
}
