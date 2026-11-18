import test from 'node:test';
import assert from 'node:assert/strict';

import {
  computeWindowArcPose,
  evaluateCalculatorExpression,
  formatElapsedStopwatch,
  LauncherStateStore,
} from './launcherState';

test('LauncherStateStore opens, focuses, pins, and closes windows cleanly', () => {
  const store = new LauncherStateStore();
  assert.equal(store.getSnapshot().openWindows.length, 0);

  const win1 = store.openApp('browser');
  assert.equal(win1.id, 'browser');
  assert.equal(win1.slotIndex, 0);
  assert.equal(win1.pinnedToHead, false);

  const win2 = store.openApp('photos');
  assert.equal(win2.slotIndex, 1);

  // Toggling pin-to-head flips pinned state
  const pinned = store.togglePinWindowToHead('browser');
  assert.equal(pinned, true);
  assert.equal(
    store.getSnapshot().openWindows.find((w) => w.id === 'browser')?.pinnedToHead,
    true
  );

  // Closing an open window frees its slot
  assert.equal(store.closeApp('browser'), true);
  assert.equal(store.closeApp('browser'), false);
  assert.equal(store.getSnapshot().openWindows.length, 1);

  const win3 = store.openApp('music');
  assert.equal(win3.slotIndex, 0);
});

test('LauncherStateStore installs and uninstalls optional Android XR apps', () => {
  const store = new LauncherStateStore();
  assert.equal(store.getSnapshot().installedApps.includes('calculator'), false);

  assert.equal(store.installApp('calculator'), true);
  assert.equal(store.getSnapshot().installedApps.includes('calculator'), true);
  assert.equal(store.installApp('calculator'), false);

  // Default system apps cannot be uninstalled
  assert.equal(store.uninstallApp('browser'), false);
  // Optional apps can be uninstalled and close their open window
  store.openApp('calculator');
  assert.equal(store.uninstallApp('calculator'), true);
  assert.equal(store.getSnapshot().installedApps.includes('calculator'), false);
  assert.equal(
    store.getSnapshot().openWindows.some((w) => w.id === 'calculator'),
    false
  );
});

test('Environment & immersion dial synchronize AR/VR state cleanly', () => {
  const store = new LauncherStateStore();
  assert.equal(store.getSnapshot().settings.environment, 'passthrough');
  assert.equal(store.getSnapshot().settings.immersion, 0);

  store.setEnvironment('alpine');
  assert.equal(store.getSnapshot().settings.environment, 'alpine');
  assert.equal(store.getSnapshot().settings.immersion, 0.85);

  store.setImmersion(0);
  assert.equal(store.getSnapshot().settings.environment, 'passthrough');

  // Increasing immersion from passthrough defaults into alpine environment
  store.setImmersion(0.6);
  assert.equal(store.getSnapshot().settings.environment, 'alpine');
  assert.equal(store.getSnapshot().settings.immersion, 0.6);
});

test('computeWindowArcPose spaces multiple windows without overlap', () => {
  const p0 = computeWindowArcPose(0);
  const p1 = computeWindowArcPose(1);
  assert.ok(p0.x < 0 && p1.x > 0);
  assert.ok(p0.z < 0 && p1.z < 0);
  assert.ok(p0.rotY > 0 && p1.rotY < 0);
});

test('Stopwatch and calculator helpers produce accurate formatted values', () => {
  assert.equal(formatElapsedStopwatch(65400), '01:05.4');
  assert.equal(evaluateCalculatorExpression(12, '×', 3), '36');
  assert.equal(evaluateCalculatorExpression(10, '÷', 0), 'Error');
});
