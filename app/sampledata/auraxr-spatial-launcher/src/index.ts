import * as xb from 'xrblocks';

import {VisionLauncherScript} from './launcher/VisionLauncherScript';

function isSoftwareWebGL(): boolean {
  if (typeof document === 'undefined') return false;
  const gl = document.createElement('canvas').getContext('webgl2');
  if (!gl) return true;
  const ext = gl.getExtension('WEBGL_debug_renderer_info');
  const param = ext ? ext.UNMASKED_RENDERER_WEBGL : gl.RENDERER;
  const gpu = String(gl.getParameter(param));
  gl.getExtension('WEBGL_lose_context')?.loseContext();
  return /SwiftShader|llvmpipe|softpipe|Software|Basic Render/i.test(gpu);
}

document.addEventListener('DOMContentLoaded', () => {
  const options = new xb.Options();
  options.setAppTitle('AuraXR Spatial Launcher');
  options.setAppDescription(
    'Convert your Android space into an immersive multi-window spatial OS.'
  );
  options.enableReticles();
  options.enableHands();
  options.enableXRTransitions();
  options.xrButton.showEnterSimulatorButton = true;

  if (isSoftwareWebGL()) {
    options.antialias = false;
    options.framebufferScaleFactor = 1;
  }

  xb.add(new VisionLauncherScript());
  void xb.init(options);
});
