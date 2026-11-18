import * as xb from 'xrblocks';

import {createBrowserHeroTexture} from '../launcher/proceduralTextures';
import {type SoundEngine} from '../launcher/soundEngine';
import {type AppDefinition, type WindowPose} from '../launcher/types';
import {SpatialWindow, type SpatialWindowCallbacks} from './SpatialWindow';

interface BrowserTabData {
  id: string;
  label: string;
  url: string;
  headline: string;
  summary: string;
  sections: ReadonlyArray<{title: string; body: string}>;
}

const BROWSER_TABS: readonly BrowserTabData[] = [
  {
    id: 'xr',
    label: 'Android XR',
    url: 'https://developer.android.com/xr',
    headline: 'Spatialize Your Android Apps',
    summary:
      'Transform handheld Android workflows into unbounded spatial windows floating naturally around your room.',
    sections: [
      {
        title: 'Multi-Window Spatial Desktop',
        body: 'Arrange Browser, Notes, Files, and 3D Studio side-by-side in an ergonomic viewing arc. Grab any outer window edge to reposition or push/pull depth.',
      },
      {
        title: 'Pin to View & Gaze Controls',
        body: 'Tap the pin icon in any window title bar to keep an Android app anchored smoothly to your head pose as you walk.',
      },
      {
        title: 'Android APK Compatibility',
        body: 'Open App Store to install additional spatial utilities like Calculator, ADB Shell, and Weather directly into your Dock.',
      },
    ],
  },
  {
    id: 'design',
    label: 'Spatial UI',
    url: 'https://spatial.design/vision-guidelines',
    headline: 'Glass Materials & Legibility',
    summary:
      'Frosted specular surfaces adapt smoothly between real-world passthrough and full VR environments.',
    sections: [
      {
        title: 'Ergonomic Viewing Distances',
        body: 'Primary workspace windows rest at 1.24 meters with gentle inward yaw so text remains effortless to read.',
      },
      {
        title: 'Immersive Environments',
        body: 'Dial up Mount Haleakala, Lunar Horizon, or Midnight Studio from 0% passthrough to 100% immersive VR.',
      },
    ],
  },
  {
    id: 'news',
    label: 'Release Notes',
    url: 'https://auraxr.local/changelog',
    headline: 'AuraXR Launcher 2.0',
    summary:
      'Native XR Blocks spatial runtime with procedural 3D environments and zero-latency audio feedback.',
    sections: [
      {
        title: 'Spatial 3D Inspector',
        body: 'Inspect multi-part PBR hardware models directly beside your Android windows with real-time finish switching.',
      },
      {
        title: 'Procedural Spatial Audio',
        body: 'Synthesized spatial chimes and multi-track ambient chords play directly via Web Audio.',
      },
    ],
  },
];

export function createBrowserWindow(
  app: AppDefinition,
  pose: WindowPose,
  callbacks: SpatialWindowCallbacks,
  sound: SoundEngine
): SpatialWindow {
  let activeIndex = 0;
  let bookmarked = false;

  const heroTexture = createBrowserHeroTexture();
  const urlText = new xb.UIText({
    text: BROWSER_TABS[0].url,
    style: {fontSize: 13, opacity: 0.78, flexGrow: 1},
  });

  const bookmarkIcon = new xb.UIIcon({
    icon: 'bookmark',
    filled: false,
    pointerEvents: 'none',
    style: {width: 18, height: 18, color: '#ffffff'},
  });
  const bookmarkBtn = new xb.UIButton({
    ariaLabel: 'Bookmark page',
    onClick: () => {
      sound.playTap();
      bookmarked = !bookmarked;
      bookmarkIcon.filled = bookmarked;
      bookmarkBtn.style.backgroundColor = bookmarked
        ? '#2563eb'
        : 'rgba(255, 255, 255, 0.1)';
    },
    style: {
      width: 34,
      height: 34,
      borderRadius: 17,
      paddingLeft: 0,
      paddingRight: 0,
      justifyContent: 'center',
      alignItems: 'center',
      backgroundColor: 'rgba(255, 255, 255, 0.1)',
    },
    children: [bookmarkIcon],
  });

  const addressBar = new xb.UIPanel({
    style: {
      width: '100%',
      flexDirection: 'row',
      alignItems: 'center',
      gap: 10,
      paddingTop: 6,
      paddingBottom: 6,
      paddingLeft: 14,
      paddingRight: 8,
      borderRadius: 20,
      backgroundColor: 'rgba(255, 255, 255, 0.08)',
      flexShrink: 0,
    },
    children: [
      new xb.UIIcon({
        icon: 'lock',
        pointerEvents: 'none',
        style: {width: 16, height: 16, color: '#93c5fd'},
      }),
      urlText,
      bookmarkBtn,
    ],
  });

  const headlineText = new xb.UIText({
    text: BROWSER_TABS[0].headline,
    style: {fontSize: 22, fontWeight: 'bold'},
  });
  const summaryText = new xb.UIText({
    text: BROWSER_TABS[0].summary,
    style: {fontSize: 14, lineHeight: 1.35, opacity: 0.85},
  });

  const sectionElements = [0, 1, 2].map(() => {
    const title = new xb.UIText({
      text: '',
      style: {fontSize: 16, fontWeight: 'bold'},
    });
    const body = new xb.UIText({
      text: '',
      style: {fontSize: 13, lineHeight: 1.35, opacity: 0.78},
    });
    const panel = new xb.UIPanel({
      style: {
        width: '100%',
        flexDirection: 'column',
        gap: 4,
        padding: 12,
        borderRadius: 14,
        backgroundColor: 'rgba(255, 255, 255, 0.06)',
      },
      children: [title, body],
    });
    return {panel, title, body};
  });

  const scrollView = new xb.UIScrollView({
    ariaLabel: 'Browser page content',
    style: {width: '100%', flexGrow: 1, flexBasis: 0, minHeight: 110, gap: 12},
    children: [
      new xb.UIImage({
        src: heroTexture,
        ariaLabel: 'Android XR Spatial Web Banner',
        style: {
          width: '100%',
          height: 120,
          objectFit: 'cover',
          borderRadius: 14,
        },
      }),
      headlineText,
      summaryText,
      ...sectionElements.map((s) => s.panel),
    ],
  });

  const tabButtons: xb.UIButton[] = [];
  const selectTab = (idx: number) => {
    activeIndex = idx;
    const tab = BROWSER_TABS[idx];
    urlText.text = tab.url;
    headlineText.text = tab.headline;
    summaryText.text = tab.summary;
    sectionElements.forEach((el, sIdx) => {
      const sec = tab.sections[sIdx];
      el.panel.visible = Boolean(sec);
      el.panel.style.display = sec ? 'flex' : 'none';
      if (sec) {
        el.title.text = sec.title;
        el.body.text = sec.body;
      }
    });
    tabButtons.forEach((btn, bIdx) => {
      btn.style.backgroundColor =
        bIdx === activeIndex
          ? 'rgba(59, 130, 246, 0.6)'
          : 'rgba(255, 255, 255, 0.08)';
    });
    scrollView.scrollTo(0);
  };

  BROWSER_TABS.forEach((tab, idx) => {
    const btn = new xb.UIButton({
      label: tab.label,
      onClick: () => {
        sound.playTap();
        selectTab(idx);
      },
      style: {
        height: 36,
        flexGrow: 1,
        flexBasis: 0,
        borderRadius: 18,
        fontSize: 13,
      },
    });
    tabButtons.push(btn);
  });

  const tabRow = new xb.UIPanel({
    style: {width: '100%', flexDirection: 'row', gap: 8, flexShrink: 0},
    children: tabButtons,
  });

  selectTab(0);

  const win = new SpatialWindow(app, pose, callbacks, [
    tabRow,
    addressBar,
    scrollView,
  ]);
  win.registerCleanup(() => heroTexture.dispose());
  return win;
}
