import * as THREE from 'three';
import {RoomEnvironment} from 'three/addons/environments/RoomEnvironment.js';
import * as xb from 'xrblocks';

import {
  createEarthTexture,
  createStudioFloorTexture,
} from '../launcher/proceduralTextures';
import {type EnvironmentId} from '../launcher/types';

function disableRaycasts(root: THREE.Object3D): void {
  root.traverse((obj) => {
    (obj as THREE.Object3D & {xb?: xb.XBObjectOptions}).xb = {
      pointerEvents: 'none',
    };
  });
}

function buildMountainRingGeometry(
  innerRadius: number,
  outerRadius: number,
  segments: number,
  baseY: number,
  peakHeight: number,
  seed: number,
  lowColor: THREE.Color,
  peakColor: THREE.Color
): THREE.BufferGeometry {
  const positions: number[] = [];
  const colors: number[] = [];
  const indices: number[] = [];

  for (let i = 0; i <= segments; i++) {
    const t = (i / segments) * Math.PI * 2;
    const cos = Math.cos(t);
    const sin = Math.sin(t);
    const wave =
      0.5 +
      0.3 * Math.sin(t * 5 + seed) +
      0.2 * Math.cos(t * 11 - seed * 1.7) +
      0.15 * Math.sin(t * 17 + seed * 0.6);
    const h = Math.max(0.2, wave) * peakHeight;

    // Bottom inner vertex
    positions.push(cos * innerRadius, baseY, sin * innerRadius);
    colors.push(lowColor.r, lowColor.g, lowColor.b);

    // Ridge crest vertex
    const midR = (innerRadius + outerRadius) * 0.5;
    positions.push(cos * midR, baseY + h, sin * midR);
    const mix = THREE.MathUtils.clamp(h / peakHeight, 0, 1);
    const crestColor = lowColor.clone().lerp(peakColor, mix);
    colors.push(crestColor.r, crestColor.g, crestColor.b);
  }

  for (let i = 0; i < segments; i++) {
    const a = i * 2;
    const b = a + 1;
    const c = a + 2;
    const d = a + 3;
    indices.push(a, b, c, b, d, c);
  }

  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute(
    'position',
    new THREE.Float32BufferAttribute(positions, 3)
  );
  geometry.setAttribute('color', new THREE.Float32BufferAttribute(colors, 3));
  geometry.setIndex(indices);
  geometry.computeVertexNormals();
  return geometry;
}

/**
 * Manages procedural 3D VR environments (Mount Haleakala, Lunar Horizon,
 * Midnight Studio), studio IBL lighting, and smooth AR <-> VR immersion fading.
 */
export class SpatialEnvironments extends xb.Script {
  private targetEnvironment: EnvironmentId = 'passthrough';
  private targetImmersion = 0;
  private currentImmersion = 0;

  private envRoot = new THREE.Group();
  private alpineGroup = new THREE.Group();
  private lunarGroup = new THREE.Group();
  private obsidianGroup = new THREE.Group();

  private alpineSkyMat!: THREE.ShaderMaterial;
  private lunarSkyMat!: THREE.ShaderMaterial;
  private studioSkyMat!: THREE.ShaderMaterial;
  private earthMesh?: THREE.Mesh;
  private starsPoints?: THREE.Points;
  private disposables: Array<{dispose: () => void}> = [];

  init(): void {
    this.setupLightingAndEnvironment();
    this.buildAlpineEnvironment();
    this.buildLunarEnvironment();
    this.buildObsidianStudio();

    this.envRoot.name = 'SpatialEnvironmentsRoot';
    this.envRoot.add(this.alpineGroup, this.lunarGroup, this.obsidianGroup);
    disableRaycasts(this.envRoot);
    this.add(this.envRoot);
    this.applyEnvironmentVisibility(0);
  }

  private setupLightingAndEnvironment(): void {
    const renderer = xb.core.renderer;
    if (renderer && 'compile' in renderer) {
      const pmrem = new THREE.PMREMGenerator(renderer as THREE.WebGLRenderer);
      const envTex = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
      xb.core.scene.environment = envTex;
      this.disposables.push(envTex);
      pmrem.dispose();
    }

    const hemi = new THREE.HemisphereLight(0xdbeafe, 0x334155, 1.8);
    const key = new THREE.DirectionalLight(0xfff1e0, 2.2);
    key.position.set(2.5, 4.5, 2.0);
    const rim = new THREE.DirectionalLight(0x60a5fa, 0.9);
    rim.position.set(-3.0, 2.0, -3.5);
    this.add(hemi, key, rim);
  }

  private createGradientSky(
    zenithHex: number,
    midHex: number,
    horizonHex: number
  ): {mesh: THREE.Mesh; material: THREE.ShaderMaterial} {
    const geo = new THREE.SphereGeometry(38, 32, 20);
    const material = new THREE.ShaderMaterial({
      uniforms: {
        uZenith: {value: new THREE.Color(zenithHex)},
        uMid: {value: new THREE.Color(midHex)},
        uHorizon: {value: new THREE.Color(horizonHex)},
        uOpacity: {value: 0},
      },
      vertexShader: `
        varying vec3 vLocalPos;
        void main() {
          vLocalPos = position;
          gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
        }
      `,
      fragmentShader: `
        uniform vec3 uZenith;
        uniform vec3 uMid;
        uniform vec3 uHorizon;
        uniform float uOpacity;
        varying vec3 vLocalPos;
        void main() {
          float h = clamp(normalize(vLocalPos).y, -0.2, 1.0);
          vec3 col = mix(uHorizon, uMid, smoothstep(-0.05, 0.28, h));
          col = mix(col, uZenith, smoothstep(0.28, 0.9, h));
          gl_FragColor = vec4(col, uOpacity);
        }
      `,
      side: THREE.BackSide,
      transparent: true,
      depthWrite: false,
    });
    const mesh = new THREE.Mesh(geo, material);
    mesh.renderOrder = -60;
    this.disposables.push(geo, material);
    return {mesh, material};
  }

  private buildAlpineEnvironment(): void {
    const sky = this.createGradientSky(0x0b1224, 0x2f2546, 0xd97742);
    this.alpineSkyMat = sky.material;
    this.alpineGroup.add(sky.mesh);

    const ridgeNearGeo = buildMountainRingGeometry(
      14,
      20,
      96,
      -0.8,
      3.8,
      0.5,
      new THREE.Color(0x161423),
      new THREE.Color(0x523c58)
    );
    const ridgeNearMat = new THREE.MeshStandardMaterial({
      vertexColors: true,
      roughness: 0.9,
      metalness: 0.05,
      transparent: true,
      opacity: 0,
      depthWrite: false,
      side: THREE.DoubleSide,
    });
    const ridgeNear = new THREE.Mesh(ridgeNearGeo, ridgeNearMat);
    ridgeNear.renderOrder = -42;

    const ridgeFarGeo = buildMountainRingGeometry(
      23,
      31,
      96,
      -1.0,
      6.4,
      2.2,
      new THREE.Color(0x231d36),
      new THREE.Color(0xc26d4a)
    );
    const ridgeFarMat = new THREE.MeshStandardMaterial({
      vertexColors: true,
      roughness: 0.95,
      metalness: 0.02,
      transparent: true,
      opacity: 0,
      depthWrite: false,
      side: THREE.DoubleSide,
    });
    const ridgeFar = new THREE.Mesh(ridgeFarGeo, ridgeFarMat);
    ridgeFar.renderOrder = -46;

    const floorGeo = new THREE.CircleGeometry(18, 48);
    floorGeo.rotateX(-Math.PI / 2);
    const floorMat = new THREE.MeshStandardMaterial({
      color: 0x15141e,
      roughness: 0.88,
      metalness: 0.08,
      transparent: true,
      opacity: 0,
      depthWrite: false,
    });
    const floor = new THREE.Mesh(floorGeo, floorMat);
    floor.position.y = -0.2;
    floor.renderOrder = -38;

    this.disposables.push(
      ridgeNearGeo,
      ridgeNearMat,
      ridgeFarGeo,
      ridgeFarMat,
      floorGeo,
      floorMat
    );
    this.alpineGroup.add(ridgeFar, ridgeNear, floor);
  }

  private buildLunarEnvironment(): void {
    const sky = this.createGradientSky(0x02040a, 0x060b19, 0x131d36);
    this.lunarSkyMat = sky.material;
    this.lunarGroup.add(sky.mesh);

    // Starfield
    const count = 320;
    const starPositions = new Float32Array(count * 3);
    for (let i = 0; i < count; i++) {
      const theta = ((i * 137.5) % 360) * (Math.PI / 180);
      const phi = 0.15 + ((i * 29) % 100) * 0.011;
      const r = 30;
      starPositions[i * 3] = r * Math.cos(phi) * Math.cos(theta);
      starPositions[i * 3 + 1] = r * Math.sin(phi) + 1;
      starPositions[i * 3 + 2] = r * Math.cos(phi) * Math.sin(theta);
    }
    const starsGeo = new THREE.BufferGeometry();
    starsGeo.setAttribute(
      'position',
      new THREE.BufferAttribute(starPositions, 3)
    );
    const starsMat = new THREE.PointsMaterial({
      color: 0xe2e8f0,
      size: 0.16,
      transparent: true,
      opacity: 0,
      depthWrite: false,
    });
    this.starsPoints = new THREE.Points(starsGeo, starsMat);
    this.starsPoints.renderOrder = -55;

    // Rising Earth sphere
    const earthTex = createEarthTexture();
    const earthGeo = new THREE.SphereGeometry(2.6, 36, 24);
    const earthMat = new THREE.MeshStandardMaterial({
      map: earthTex,
      roughness: 0.65,
      metalness: 0.1,
      transparent: true,
      opacity: 0,
      depthWrite: false,
    });
    this.earthMesh = new THREE.Mesh(earthGeo, earthMat);
    this.earthMesh.position.set(4.5, 6.2, -16);
    this.earthMesh.renderOrder = -50;

    // Lunar crater rim & ground
    const craterGeo = buildMountainRingGeometry(
      14,
      22,
      72,
      -0.6,
      2.3,
      4.1,
      new THREE.Color(0x1b202b),
      new THREE.Color(0x475569)
    );
    const craterMat = new THREE.MeshStandardMaterial({
      vertexColors: true,
      roughness: 0.95,
      transparent: true,
      opacity: 0,
      depthWrite: false,
      side: THREE.DoubleSide,
    });
    const crater = new THREE.Mesh(craterGeo, craterMat);
    crater.renderOrder = -42;

    const floorGeo = new THREE.CircleGeometry(16, 48);
    floorGeo.rotateX(-Math.PI / 2);
    const floorMat = new THREE.MeshStandardMaterial({
      color: 0x1e2430,
      roughness: 0.94,
      transparent: true,
      opacity: 0,
      depthWrite: false,
    });
    const floor = new THREE.Mesh(floorGeo, floorMat);
    floor.position.y = -0.2;
    floor.renderOrder = -38;

    this.disposables.push(
      starsGeo,
      starsMat,
      earthTex,
      earthGeo,
      earthMat,
      craterGeo,
      craterMat,
      floorGeo,
      floorMat
    );
    this.lunarGroup.add(this.starsPoints, this.earthMesh, crater, floor);
  }

  private buildObsidianStudio(): void {
    const sky = this.createGradientSky(0x05070c, 0x0b101b, 0x172033);
    this.studioSkyMat = sky.material;
    this.obsidianGroup.add(sky.mesh);

    const floorTex = createStudioFloorTexture();
    const floorGeo = new THREE.CircleGeometry(14, 48);
    floorGeo.rotateX(-Math.PI / 2);
    const floorMat = new THREE.MeshStandardMaterial({
      map: floorTex,
      roughness: 0.35,
      metalness: 0.25,
      transparent: true,
      opacity: 0,
      depthWrite: false,
    });
    const floor = new THREE.Mesh(floorGeo, floorMat);
    floor.position.y = -0.2;
    floor.renderOrder = -38;
    this.obsidianGroup.add(floor);
    this.disposables.push(floorTex, floorGeo, floorMat);

    // 4 architectural perimeter light monoliths
    const pillarGeo = new THREE.BoxGeometry(0.18, 3.6, 0.08);
    const pillarMat = new THREE.MeshStandardMaterial({
      color: 0x1e293b,
      emissive: 0x1d4ed8,
      emissiveIntensity: 0.35,
      roughness: 0.3,
      metalness: 0.7,
      transparent: true,
      opacity: 0,
      depthWrite: false,
    });
    this.disposables.push(pillarGeo, pillarMat);

    const angles = [-0.85, -0.35, 0.35, 0.85];
    for (const angle of angles) {
      const pillar = new THREE.Mesh(pillarGeo, pillarMat);
      const r = 6.2;
      pillar.position.set(Math.sin(angle) * r, 1.75, -Math.cos(angle) * r);
      pillar.rotation.y = -angle;
      pillar.renderOrder = -34;
      this.obsidianGroup.add(pillar);
    }
  }

  setTarget(environment: EnvironmentId, immersion: number): void {
    this.targetEnvironment = environment;
    this.targetImmersion = THREE.MathUtils.clamp(immersion, 0, 1);

    if (xb.core.transition) {
      if (environment === 'passthrough' || this.targetImmersion <= 0.02) {
        xb.core.transition.toAR();
      } else {
        const bgColor =
          environment === 'alpine'
            ? 0x141322
            : environment === 'lunar'
              ? 0x050812
              : 0x080b12;
        xb.core.transition.toVR({
          targetAlpha: Math.min(1, this.targetImmersion * 1.12),
          color: bgColor,
        });
      }
    }
  }

  update(): void {
    const dt = Math.min(0.1, xb.getDeltaTime() || 0.016);
    const desired =
      this.targetEnvironment === 'passthrough' ? 0 : this.targetImmersion;
    this.currentImmersion = THREE.MathUtils.damp(
      this.currentImmersion,
      desired,
      6,
      dt
    );

    if (this.earthMesh && this.lunarGroup.visible) {
      this.earthMesh.rotation.y += dt * 0.035;
    }

    this.applyEnvironmentVisibility(this.currentImmersion);
  }

  private applyEnvironmentVisibility(alpha: number): void {
    const visible = alpha > 0.01 && this.targetEnvironment !== 'passthrough';
    this.alpineGroup.visible = visible && this.targetEnvironment === 'alpine';
    this.lunarGroup.visible = visible && this.targetEnvironment === 'lunar';
    this.obsidianGroup.visible =
      visible && this.targetEnvironment === 'obsidian';

    const skyAlpha = Math.min(1, alpha * 1.15);
    if (this.alpineGroup.visible) {
      this.alpineSkyMat.uniforms.uOpacity.value = skyAlpha;
      this.setGroupOpacity(this.alpineGroup, skyAlpha);
    }
    if (this.lunarGroup.visible) {
      this.lunarSkyMat.uniforms.uOpacity.value = skyAlpha;
      this.setGroupOpacity(this.lunarGroup, skyAlpha);
    }
    if (this.obsidianGroup.visible) {
      this.studioSkyMat.uniforms.uOpacity.value = skyAlpha;
      this.setGroupOpacity(this.obsidianGroup, skyAlpha);
    }
  }

  private setGroupOpacity(group: THREE.Group, alpha: number): void {
    group.traverse((child) => {
      if (
        (child instanceof THREE.Mesh || child instanceof THREE.Points) &&
        child.material instanceof THREE.Material &&
        !(child.material instanceof THREE.ShaderMaterial)
      ) {
        child.material.opacity = alpha;
      }
    });
  }

  dispose(): void {
    for (const d of this.disposables) {
      d.dispose();
    }
    this.disposables = [];
  }
}
