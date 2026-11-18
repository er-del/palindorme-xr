import * as xb from 'xrblocks';

import {formatElapsedStopwatch} from '../launcher/launcherState';
import {type SoundEngine} from '../launcher/soundEngine';
import {type AppDefinition, type WindowPose} from '../launcher/types';
import {SpatialWindow, type SpatialWindowCallbacks} from './SpatialWindow';

export function createClockWindow(
  app: AppDefinition,
  pose: WindowPose,
  callbacks: SpatialWindowCallbacks,
  sound: SoundEngine
): SpatialWindow {
  let running = false;
  let elapsedMs = 0;

  const timeText = new xb.UIText({
    text: '12:00',
    style: {fontSize: 44, fontWeight: 'bold', textAlign: 'center'},
  });
  const dateText = new xb.UIText({
    text: '',
    style: {fontSize: 14, opacity: 0.72, textAlign: 'center'},
  });

  const stopwatchText = new xb.UIText({
    text: '00:00.0',
    style: {fontSize: 28, fontWeight: 'bold', textAlign: 'center'},
  });

  const startBtn = new xb.UIButton({
    label: 'Start',
    icon: 'play_arrow',
    onClick: () => {
      sound.playTap();
      running = !running;
      startBtn.label = running ? 'Pause' : 'Start';
      startBtn.icon = running ? 'pause' : 'play_arrow';
    },
    style: {flexGrow: 1, height: 38, borderRadius: 19},
  });

  const resetBtn = new xb.UIButton({
    label: 'Reset',
    icon: 'restart_alt',
    onClick: () => {
      sound.playTap();
      running = false;
      elapsedMs = 0;
      startBtn.label = 'Start';
      startBtn.icon = 'play_arrow';
      stopwatchText.text = '00:00.0';
    },
    style: {flexGrow: 1, height: 38, borderRadius: 19},
  });

  const worldRow = new xb.UIPanel({
    style: {
      width: '100%',
      flexDirection: 'row',
      justifyContent: 'space-between',
      padding: 12,
      borderRadius: 14,
      backgroundColor: 'rgba(255, 255, 255, 0.06)',
    },
    children: [
      new xb.UIText({text: 'Cupertino · Tokyo · London', style: {fontSize: 13, opacity: 0.8}}),
      new xb.UIText({text: 'Synced', style: {fontSize: 13, color: '#2dd4bf'}}),
    ],
  });

  let lastTimeStr = '';

  const stopwatchBox = new xb.UIPanel({
    style: {
      width: '100%',
      flexDirection: 'column',
      alignItems: 'center',
      gap: 10,
      padding: 14,
      borderRadius: 16,
      backgroundColor: 'rgba(255, 255, 255, 0.06)',
    },
    children: [
      new xb.UIText({
        text: 'Spatial Stopwatch',
        style: {fontSize: 13, opacity: 0.72},
      }),
      stopwatchText,
      new xb.UIPanel({
        style: {width: '100%', flexDirection: 'row', gap: 10},
        children: [startBtn, resetBtn],
      }),
    ],
  });

  const win = new SpatialWindow(app, pose, callbacks, [
    timeText,
    dateText,
    stopwatchBox,
    worldRow,
  ]);
  win.registerTicker((dt) => {
    const now = new Date();
    const timeStr = now.toLocaleTimeString([], {
      hour: '2-digit',
      minute: '2-digit',
    });
    if (timeStr !== lastTimeStr) {
      lastTimeStr = timeStr;
      timeText.text = timeStr;
      dateText.text = now.toLocaleDateString([], {
        weekday: 'long',
        month: 'short',
        day: 'numeric',
      });
    }
    if (running) {
      elapsedMs += dt * 1000;
      stopwatchText.text = formatElapsedStopwatch(elapsedMs);
    }
  });
  return win;
}
