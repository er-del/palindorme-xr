import * as THREE from 'three';
import * as xb from 'xrblocks';

import {MUSIC_TRACKS} from '../launcher/launcherState';
import {createAlbumArtTexture} from '../launcher/proceduralTextures';
import {type SoundEngine} from '../launcher/soundEngine';
import {type AppDefinition, type WindowPose} from '../launcher/types';
import {SpatialWindow, type SpatialWindowCallbacks} from './SpatialWindow';

export function createMusicWindow(
  app: AppDefinition,
  pose: WindowPose,
  callbacks: SpatialWindowCallbacks,
  sound: SoundEngine
): SpatialWindow {
  let trackIndex = 0;
  let playing = false;
  let volume = 0.45;

  const albumTextures = MUSIC_TRACKS.map((t) =>
    createAlbumArtTexture(t.palette, t.title)
  );

  const artImage = new xb.UIImage({
    src: albumTextures[0],
    ariaLabel: 'Track album artwork',
    style: {
      width: 140,
      height: 140,
      borderRadius: 18,
      objectFit: 'cover',
    },
  });

  const trackTitle = new xb.UIText({
    text: MUSIC_TRACKS[0].title,
    style: {fontSize: 22, fontWeight: 'bold'},
  });
  const trackArtist = new xb.UIText({
    text: `${MUSIC_TRACKS[0].artist} · ${MUSIC_TRACKS[0].bpm} BPM`,
    style: {fontSize: 14, opacity: 0.72},
  });
  const statusText = new xb.UIText({
    text: 'Paused',
    style: {fontSize: 13, opacity: 0.65},
  });

  // Animated spatial equalizer bars inside a panel
  const eqBars = [0, 1, 2, 3, 4, 5].map(
    () =>
      new xb.UIPanel({
        pointerEvents: 'none',
        style: {
          width: 8,
          height: 14,
          borderRadius: 4,
          backgroundColor: '#ef4444',
        },
      })
  );

  const eqContainer = new xb.UIPanel({
    style: {
      width: '100%',
      height: 36,
      flexDirection: 'row',
      alignItems: 'flex-end',
      justifyContent: 'flex-start',
      gap: 6,
      paddingTop: 4,
    },
    children: eqBars,
  });

  const infoCol = new xb.UIPanel({
    style: {
      flexGrow: 1,
      flexDirection: 'column',
      alignItems: 'flex-start',
      gap: 6,
    },
    children: [trackTitle, trackArtist, statusText, eqContainer],
  });

  const topRow = new xb.UIPanel({
    style: {
      width: '100%',
      flexDirection: 'row',
      alignItems: 'center',
      gap: 16,
    },
    children: [artImage, infoCol],
  });

  const playIcon = new xb.UIIcon({
    icon: 'play_arrow',
    pointerEvents: 'none',
    style: {width: 26, height: 26, color: '#ffffff'},
  });

  const updatePlaybackState = (nextPlaying: boolean) => {
    playing = nextPlaying;
    playIcon.icon = playing ? 'pause' : 'play_arrow';
    statusText.text = playing ? 'Playing Spatial Audio' : 'Paused';
    if (playing) {
      sound.startMusic(MUSIC_TRACKS[trackIndex]);
      sound.setMusicVolume(volume);
    } else {
      sound.stopMusic();
    }
  };

  const selectTrack = (nextIdx: number) => {
    trackIndex = (nextIdx + MUSIC_TRACKS.length) % MUSIC_TRACKS.length;
    const track = MUSIC_TRACKS[trackIndex];
    artImage.src = albumTextures[trackIndex];
    trackTitle.text = track.title;
    trackArtist.text = `${track.artist} · ${track.bpm} BPM`;
    if (playing) {
      sound.startMusic(track);
    }
  };

  const playButton = new xb.UIButton({
    ariaLabel: 'Play or pause music',
    onClick: () => {
      updatePlaybackState(!playing);
    },
    style: {
      width: 54,
      height: 44,
      borderRadius: 22,
      paddingLeft: 0,
      paddingRight: 0,
      backgroundColor: '#ef4444',
      justifyContent: 'center',
      alignItems: 'center',
    },
    children: [playIcon],
  });

  const transportRow = new xb.UIPanel({
    style: {
      width: '100%',
      flexDirection: 'row',
      justifyContent: 'center',
      alignItems: 'center',
      gap: 14,
    },
    children: [
      new xb.UIButton({
        icon: 'skip_previous',
        ariaLabel: 'Previous track',
        onClick: () => {
          sound.playTap();
          selectTrack(trackIndex - 1);
        },
        style: {width: 44, height: 40, borderRadius: 20, paddingLeft: 0, paddingRight: 0},
      }),
      playButton,
      new xb.UIButton({
        icon: 'skip_next',
        ariaLabel: 'Next track',
        onClick: () => {
          sound.playTap();
          selectTrack(trackIndex + 1);
        },
        style: {width: 44, height: 40, borderRadius: 20, paddingLeft: 0, paddingRight: 0},
      }),
    ],
  });

  const volumeValue = new xb.UIText({
    text: '45%',
    style: {fontSize: 13, fontWeight: 'bold'},
  });
  const volumeSlider = new xb.UISlider({
    ariaLabel: 'Music volume',
    min: 0,
    max: 100,
    step: 5,
    value: 45,
    style: {width: '100%', height: 36},
    onInput: (val) => {
      volume = val / 100;
      volumeValue.text = `${val}%`;
      sound.setMusicVolume(volume);
    },
  });

  const volumeBlock = new xb.UIPanel({
    style: {width: '100%', flexDirection: 'column', gap: 6},
    children: [
      new xb.UIPanel({
        style: {
          width: '100%',
          flexDirection: 'row',
          justifyContent: 'space-between',
        },
        children: [
          new xb.UIText({text: 'Spatial Volume', style: {fontSize: 13, opacity: 0.78}}),
          volumeValue,
        ],
      }),
      volumeSlider,
    ],
  });

  let eqTime = 0;

  const win = new SpatialWindow(app, pose, callbacks, [
    topRow,
    transportRow,
    volumeBlock,
  ]);
  win.registerTicker((dt) => {
    eqTime += dt;
    eqBars.forEach((bar, i) => {
      const targetH = playing
        ? 8 + Math.round((0.5 + 0.5 * Math.sin(eqTime * 6 + i * 1.1)) * 24)
        : 6;
      const curr = typeof bar.style.height === 'number' ? bar.style.height : 8;
      bar.style.height = Math.round(THREE.MathUtils.lerp(curr, targetH, 0.25));
    });
  });
  win.registerCleanup(() => {
    sound.stopMusic();
    for (const tex of albumTextures) {
      tex.dispose();
    }
  });
  return win;
}
