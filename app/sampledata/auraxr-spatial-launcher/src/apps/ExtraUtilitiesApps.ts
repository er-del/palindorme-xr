import * as xb from 'xrblocks';

import {evaluateCalculatorExpression} from '../launcher/launcherState';
import {type SoundEngine} from '../launcher/soundEngine';
import {type AppDefinition, type WindowPose} from '../launcher/types';
import {SpatialWindow, type SpatialWindowCallbacks} from './SpatialWindow';

export function createCalculatorWindow(
  app: AppDefinition,
  pose: WindowPose,
  callbacks: SpatialWindowCallbacks,
  sound: SoundEngine
): SpatialWindow {
  let display = '0';
  let leftOperand: number | null = null;
  let pendingOp: '+' | '-' | '×' | '÷' | null = null;
  let resetNext = false;

  const screenText = new xb.UIText({
    text: '0',
    style: {fontSize: 34, fontWeight: 'bold', textAlign: 'right', width: '100%'},
  });

  const screenBox = new xb.UIPanel({
    style: {
      width: '100%',
      padding: 14,
      borderRadius: 16,
      backgroundColor: 'rgba(0, 0, 0, 0.35)',
    },
    children: [screenText],
  });

  const pressKey = (key: string) => {
    sound.playTap();
    if (key === 'C') {
      display = '0';
      leftOperand = null;
      pendingOp = null;
      resetNext = false;
    } else if (key === '+' || key === '-' || key === '×' || key === '÷') {
      leftOperand = parseFloat(display);
      pendingOp = key;
      resetNext = true;
    } else if (key === '=') {
      if (leftOperand !== null && pendingOp) {
        display = evaluateCalculatorExpression(
          leftOperand,
          pendingOp,
          parseFloat(display)
        );
        leftOperand = null;
        pendingOp = null;
        resetNext = true;
      }
    } else {
      if (resetNext || display === '0') {
        display = key === '.' ? '0.' : key;
        resetNext = false;
      } else if (!(key === '.' && display.includes('.'))) {
        display += key;
      }
    }
    screenText.text = display;
  };

  const rows: readonly (readonly string[])[] = [
    ['7', '8', '9', '÷'],
    ['4', '5', '6', '×'],
    ['1', '2', '3', '-'],
    ['C', '0', '.', '+'],
  ];

  const keypad = new xb.UIPanel({
    style: {width: '100%', flexDirection: 'column', gap: 8},
    children: [
      ...rows.map(
        (row) =>
          new xb.UIPanel({
            style: {width: '100%', flexDirection: 'row', gap: 8},
            children: row.map((key) => {
              const isOp = ['+', '-', '×', '÷'].includes(key);
              return new xb.UIButton({
                label: key,
                onClick: () => pressKey(key),
                style: {
                  flexGrow: 1,
                  height: 44,
                  borderRadius: 14,
                  backgroundColor: isOp
                    ? 'rgba(249, 115, 22, 0.65)'
                    : 'rgba(255, 255, 255, 0.1)',
                },
              });
            }),
          })
      ),
      new xb.UIButton({
        label: '=',
        onClick: () => pressKey('='),
        style: {
          width: '100%',
          height: 44,
          borderRadius: 14,
          backgroundColor: '#f97316',
        },
      }),
    ],
  });

  return new SpatialWindow(app, pose, callbacks, [screenBox, keypad]);
}

export function createTerminalWindow(
  app: AppDefinition,
  pose: WindowPose,
  callbacks: SpatialWindowCallbacks,
  sound: SoundEngine
): SpatialWindow {
  const outputLines = [
    'Android XR Bridge (OpenXR 1.1)',
    '$ adb devices -> emulator-xr-01  device',
    '$ wm density -> spatial-240dpi',
  ];

  const logText = new xb.UIText({
    text: outputLines.join('\n'),
    style: {
      width: '100%',
      fontSize: 13,
      lineHeight: 1.4,
      whiteSpace: 'pre-line',
      color: '#86efac',
    },
  });

  const scrollBox = new xb.UIScrollView({
    ariaLabel: 'ADB shell output',
    style: {
      flexGrow: 1,
      flexBasis: 0,
      minHeight: 160,
      padding: 12,
      borderRadius: 14,
      backgroundColor: 'rgba(2, 6, 23, 0.72)',
    },
    children: [logText],
  });

  const runCmd = (cmd: string, reply: string) => {
    sound.playTap();
    outputLines.push(`$ ${cmd}`, reply);
    logText.text = outputLines.slice(-14).join('\n');
    scrollBox.scrollTo(scrollBox.maxScrollTop);
  };

  const quickCommands = new xb.UIPanel({
    style: {width: '100%', flexDirection: 'row', gap: 8},
    children: [
      new xb.UIButton({
        label: 'List Packages',
        onClick: () =>
          runCmd(
            'pm list packages -3',
            'package:com.auraxr.launcher\npackage:com.auraxr.studio3d'
          ),
        style: {flexGrow: 1, height: 36, borderRadius: 18, fontSize: 12},
      }),
      new xb.UIButton({
        label: 'XR Display',
        onClick: () =>
          runCmd(
            'dumpsys display | grep XR',
            'Stereo 4K Micro-OLED · 90Hz · Passthrough OK'
          ),
        style: {flexGrow: 1, height: 36, borderRadius: 18, fontSize: 12},
      }),
      new xb.UIButton({
        label: 'Clear',
        onClick: () => {
          sound.playTap();
          outputLines.length = 0;
          outputLines.push('Android XR Bridge ready.');
          logText.text = outputLines[0];
        },
        style: {height: 36, borderRadius: 18, fontSize: 12},
      }),
    ],
  });

  return new SpatialWindow(app, pose, callbacks, [quickCommands, scrollBox]);
}

export function createWeatherWindow(
  app: AppDefinition,
  pose: WindowPose,
  callbacks: SpatialWindowCallbacks,
  sound: SoundEngine
): SpatialWindow {
  const cities = [
    {city: 'Mountain View', temp: '21°C', cond: 'Clear Twilight · Wind 8 km/h'},
    {city: 'Haleakala Summit', temp: '11°C', cond: 'High Ridge Cloud · 2,900m'},
    {city: 'Tokyo Bay', temp: '17°C', cond: 'Light Mist · Humidity 68%'},
  ];
  let idx = 0;

  const cityText = new xb.UIText({
    text: cities[0].city,
    style: {fontSize: 22, fontWeight: 'bold', textAlign: 'center'},
  });
  const tempText = new xb.UIText({
    text: cities[0].temp,
    style: {fontSize: 48, fontWeight: 'bold', textAlign: 'center'},
  });
  const condText = new xb.UIText({
    text: cities[0].cond,
    style: {fontSize: 14, opacity: 0.78, textAlign: 'center'},
  });

  const nextCityBtn = new xb.UIButton({
    label: 'Switch Location',
    icon: 'my_location',
    onClick: () => {
      sound.playTap();
      idx = (idx + 1) % cities.length;
      cityText.text = cities[idx].city;
      tempText.text = cities[idx].temp;
      condText.text = cities[idx].cond;
    },
    style: {width: '100%', height: 38, borderRadius: 19},
  });

  return new SpatialWindow(app, pose, callbacks, [
    cityText,
    tempText,
    condText,
    nextCityBtn,
  ]);
}
