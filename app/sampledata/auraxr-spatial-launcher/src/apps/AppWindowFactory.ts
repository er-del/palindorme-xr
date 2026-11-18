import {getAppDefinition, type LauncherStateStore} from '../launcher/launcherState';
import {type SoundEngine} from '../launcher/soundEngine';
import {type AppId, type WindowPose} from '../launcher/types';
import {createBrowserWindow} from './BrowserApp';
import {createClockWindow} from './ClockApp';
import {
  createCalculatorWindow,
  createTerminalWindow,
  createWeatherWindow,
} from './ExtraUtilitiesApps';
import {createFilesWindow} from './FilesApp';
import {createMusicWindow} from './MusicApp';
import {createNotesWindow} from './NotesApp';
import {createPhotosWindow} from './PhotosApp';
import {type SpatialWindow, type SpatialWindowCallbacks} from './SpatialWindow';
import {createStoreWindow} from './StoreApp';
import {createStudio3DWindow} from './Studio3DApp';

export function createAppWindow(
  appId: AppId,
  pose: WindowPose,
  callbacks: SpatialWindowCallbacks,
  store: LauncherStateStore,
  sound: SoundEngine,
  onOpenApp: (id: AppId) => void
): SpatialWindow {
  const app = getAppDefinition(appId);
  switch (appId) {
    case 'browser':
      return createBrowserWindow(app, pose, callbacks, sound);
    case 'photos':
      return createPhotosWindow(app, pose, callbacks, sound);
    case 'music':
      return createMusicWindow(app, pose, callbacks, sound);
    case 'notes':
      return createNotesWindow(app, pose, callbacks, sound);
    case 'files':
      return createFilesWindow(app, pose, callbacks, sound, onOpenApp);
    case 'studio3d':
      return createStudio3DWindow(app, pose, callbacks, sound);
    case 'clock':
      return createClockWindow(app, pose, callbacks, sound);
    case 'store':
      return createStoreWindow(app, pose, callbacks, store, sound, onOpenApp);
    case 'calculator':
      return createCalculatorWindow(app, pose, callbacks, sound);
    case 'terminal':
      return createTerminalWindow(app, pose, callbacks, sound);
    case 'weather':
      return createWeatherWindow(app, pose, callbacks, sound);
  }
}
