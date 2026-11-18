import * as THREE from 'three';

function createCanvas(width: number, height: number): {
  canvas: HTMLCanvasElement;
  ctx: CanvasRenderingContext2D;
} {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d')!;
  return {canvas, ctx};
}

function finalizeTexture(canvas: HTMLCanvasElement): THREE.CanvasTexture {
  const tex = new THREE.CanvasTexture(canvas);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.minFilter = THREE.LinearFilter;
  tex.magFilter = THREE.LinearFilter;
  tex.needsUpdate = true;
  return tex;
}

export interface PhotoPreset {
  id: string;
  title: string;
  location: string;
  aspect: string;
  texture: THREE.CanvasTexture;
}

export function createPhotoGalleryTextures(): PhotoPreset[] {
  return [
    {
      id: 'alpine-sunrise',
      title: 'Haleakala Crater',
      location: 'Maui, Hawaii · Spatial Panorama',
      aspect: '16:9',
      texture: buildAlpinePhoto(),
    },
    {
      id: 'tokyo-rain',
      title: 'Shibuya Twilight',
      location: 'Tokyo, Japan · Depth Capture',
      aspect: '16:9',
      texture: buildCityNightPhoto(),
    },
    {
      id: 'pacific-coast',
      title: 'Big Sur Mist',
      location: 'California · 3D Spatial Frame',
      aspect: '16:9',
      texture: buildCoastPhoto(),
    },
    {
      id: 'lunar-arc',
      title: 'Orbital Sunrise',
      location: 'Low Earth Orbit · Archive',
      aspect: '16:9',
      texture: buildOrbitalPhoto(),
    },
  ];
}

function buildAlpinePhoto(): THREE.CanvasTexture {
  const {canvas, ctx} = createCanvas(960, 540);
  const sky = ctx.createLinearGradient(0, 0, 0, 540);
  sky.addColorStop(0, '#0f172a');
  sky.addColorStop(0.45, '#3b2d54');
  sky.addColorStop(0.72, '#d97757');
  sky.addColorStop(1, '#fbbf24');
  ctx.fillStyle = sky;
  ctx.fillRect(0, 0, 960, 540);

  // Soft sun glow
  const sun = ctx.createRadialGradient(640, 290, 10, 640, 290, 220);
  sun.addColorStop(0, 'rgba(255, 244, 214, 0.95)');
  sun.addColorStop(0.3, 'rgba(251, 191, 36, 0.35)');
  sun.addColorStop(1, 'rgba(251, 191, 36, 0)');
  ctx.fillStyle = sun;
  ctx.fillRect(0, 0, 960, 540);

  // Layered ridges
  const layers = [
    {yBase: 330, amp: 55, freq: 0.006, color: '#2b2238'},
    {yBase: 390, amp: 45, freq: 0.009, color: '#1b1626'},
    {yBase: 455, amp: 32, freq: 0.012, color: '#0d0c14'},
  ];
  layers.forEach((layer, idx) => {
    ctx.beginPath();
    ctx.moveTo(0, 540);
    for (let x = 0; x <= 960; x += 12) {
      const y =
        layer.yBase +
        Math.sin(x * layer.freq + idx * 1.4) * layer.amp +
        Math.cos(x * layer.freq * 2.3 + idx) * (layer.amp * 0.45);
      ctx.lineTo(x, y);
    }
    ctx.lineTo(960, 540);
    ctx.closePath();
    ctx.fillStyle = layer.color;
    ctx.fill();
  });
  return finalizeTexture(canvas);
}

function buildCityNightPhoto(): THREE.CanvasTexture {
  const {canvas, ctx} = createCanvas(960, 540);
  const bg = ctx.createLinearGradient(0, 0, 0, 540);
  bg.addColorStop(0, '#090d16');
  bg.addColorStop(0.65, '#172554');
  bg.addColorStop(1, '#0f172a');
  ctx.fillStyle = bg;
  ctx.fillRect(0, 0, 960, 540);

  // Architectural towers with warm/cool lit windows
  for (let i = 0; i < 16; i++) {
    const x = i * 62 - 10;
    const w = 52 + (i % 3) * 14;
    const h = 190 + ((i * 73) % 210);
    const y = 540 - h;
    ctx.fillStyle = i % 2 === 0 ? '#0b1220' : '#111c33';
    ctx.fillRect(x, y, w, h);

    for (let wy = y + 18; wy < 510; wy += 22) {
      for (let wx = x + 10; wx < x + w - 12; wx += 14) {
        if (((wx + wy + i) % 5) === 0) continue;
        ctx.fillStyle =
          (wx + i) % 3 === 0
            ? 'rgba(251, 191, 36, 0.7)'
            : 'rgba(125, 211, 252, 0.65)';
        ctx.fillRect(wx, wy, 6, 9);
      }
    }
  }
  return finalizeTexture(canvas);
}

function buildCoastPhoto(): THREE.CanvasTexture {
  const {canvas, ctx} = createCanvas(960, 540);
  const sky = ctx.createLinearGradient(0, 0, 0, 320);
  sky.addColorStop(0, '#1e293b');
  sky.addColorStop(1, '#64748b');
  ctx.fillStyle = sky;
  ctx.fillRect(0, 0, 960, 540);

  const sea = ctx.createLinearGradient(0, 290, 0, 540);
  sea.addColorStop(0, '#0f3443');
  sea.addColorStop(1, '#0b1f29');
  ctx.fillStyle = sea;
  ctx.fillRect(0, 290, 960, 250);

  // Coastal headland
  ctx.beginPath();
  ctx.moveTo(0, 230);
  ctx.quadraticCurveTo(220, 260, 420, 330);
  ctx.lineTo(0, 420);
  ctx.closePath();
  ctx.fillStyle = '#111827';
  ctx.fill();

  // Sea mist band
  const mist = ctx.createLinearGradient(0, 270, 0, 360);
  mist.addColorStop(0, 'rgba(226, 232, 240, 0)');
  mist.addColorStop(0.5, 'rgba(226, 232, 240, 0.25)');
  mist.addColorStop(1, 'rgba(226, 232, 240, 0)');
  ctx.fillStyle = mist;
  ctx.fillRect(0, 270, 960, 90);
  return finalizeTexture(canvas);
}

function buildOrbitalPhoto(): THREE.CanvasTexture {
  const {canvas, ctx} = createCanvas(960, 540);
  ctx.fillStyle = '#020617';
  ctx.fillRect(0, 0, 960, 540);

  for (let i = 0; i < 120; i++) {
    const sx = (i * 197) % 960;
    const sy = (i * 113) % 340;
    ctx.fillStyle = `rgba(255,255,255,${0.3 + (i % 5) * 0.14})`;
    ctx.fillRect(sx, sy, 2, 2);
  }

  const limb = ctx.createRadialGradient(480, 980, 560, 480, 980, 740);
  limb.addColorStop(0, '#0369a1');
  limb.addColorStop(0.75, '#38bdf8');
  limb.addColorStop(0.84, 'rgba(125, 211, 252, 0.35)');
  limb.addColorStop(1, 'rgba(2, 6, 23, 0)');
  ctx.fillStyle = limb;
  ctx.fillRect(0, 0, 960, 540);
  return finalizeTexture(canvas);
}

export function createAlbumArtTexture(
  palette: readonly [string, string, string],
  title: string
): THREE.CanvasTexture {
  const {canvas, ctx} = createCanvas(512, 512);
  const grad = ctx.createLinearGradient(0, 0, 512, 512);
  grad.addColorStop(0, palette[0]);
  grad.addColorStop(0.55, palette[1]);
  grad.addColorStop(1, palette[2]);
  ctx.fillStyle = grad;
  ctx.fillRect(0, 0, 512, 512);

  // Concentric spatial vinyl/wave rings
  ctx.strokeStyle = 'rgba(255,255,255,0.24)';
  ctx.lineWidth = 3;
  [80, 130, 180, 225].forEach((r) => {
    ctx.beginPath();
    ctx.arc(256, 256, r, 0, Math.PI * 2);
    ctx.stroke();
  });

  ctx.fillStyle = 'rgba(255,255,255,0.92)';
  ctx.font = '600 32px sans-serif';
  ctx.textAlign = 'center';
  ctx.fillText(title, 256, 456);
  return finalizeTexture(canvas);
}

export function createBrowserHeroTexture(): THREE.CanvasTexture {
  const {canvas, ctx} = createCanvas(900, 300);
  const grad = ctx.createLinearGradient(0, 0, 900, 300);
  grad.addColorStop(0, '#0f172a');
  grad.addColorStop(0.5, '#1d4ed8');
  grad.addColorStop(1, '#0ea5e9');
  ctx.fillStyle = grad;
  ctx.fillRect(0, 0, 900, 300);

  ctx.strokeStyle = 'rgba(255,255,255,0.18)';
  ctx.lineWidth = 2;
  for (let r = 60; r <= 240; r += 45) {
    ctx.beginPath();
    ctx.arc(720, 150, r, 0, Math.PI * 2);
    ctx.stroke();
  }

  ctx.fillStyle = '#ffffff';
  ctx.font = 'bold 42px sans-serif';
  ctx.fillText('Android XR Spatial Web', 48, 135);
  ctx.fillStyle = 'rgba(226, 232, 240, 0.88)';
  ctx.font = '24px sans-serif';
  ctx.fillText('Multi-window glass workspace · 6DoF spatial computing', 48, 182);
  return finalizeTexture(canvas);
}

export function createEarthTexture(): THREE.CanvasTexture {
  const {canvas, ctx} = createCanvas(512, 256);
  ctx.fillStyle = '#0b2d59';
  ctx.fillRect(0, 0, 512, 256);

  // Stylized procedural continents
  ctx.fillStyle = '#2d6a4f';
  const blobs = [
    [110, 95, 58, 36],
    [160, 145, 34, 48],
    [290, 82, 74, 42],
    [335, 135, 42, 46],
    [420, 165, 38, 24],
  ];
  for (const [cx, cy, rx, ry] of blobs) {
    ctx.beginPath();
    ctx.ellipse(cx, cy, rx, ry, 0.3, 0, Math.PI * 2);
    ctx.fill();
  }

  // Soft cloud bands
  ctx.fillStyle = 'rgba(255,255,255,0.34)';
  for (let i = 0; i < 18; i++) {
    ctx.beginPath();
    ctx.ellipse(
      (i * 67) % 512,
      30 + ((i * 43) % 190),
      44,
      9,
      0.1,
      0,
      Math.PI * 2
    );
    ctx.fill();
  }
  return finalizeTexture(canvas);
}

export function createStudioFloorTexture(): THREE.CanvasTexture {
  const {canvas, ctx} = createCanvas(512, 512);
  ctx.fillStyle = '#090c12';
  ctx.fillRect(0, 0, 512, 512);

  const glow = ctx.createRadialGradient(256, 256, 12, 256, 256, 250);
  glow.addColorStop(0, 'rgba(56, 189, 248, 0.14)');
  glow.addColorStop(0.6, 'rgba(15, 23, 42, 0.08)');
  glow.addColorStop(1, 'rgba(9, 12, 18, 0)');
  ctx.fillStyle = glow;
  ctx.fillRect(0, 0, 512, 512);

  ctx.strokeStyle = 'rgba(148, 163, 184, 0.14)';
  ctx.lineWidth = 1.5;
  [48, 96, 144, 192, 240].forEach((r) => {
    ctx.beginPath();
    ctx.arc(256, 256, r, 0, Math.PI * 2);
    ctx.stroke();
  });
  return finalizeTexture(canvas);
}
