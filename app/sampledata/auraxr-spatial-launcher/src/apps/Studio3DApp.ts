import * as THREE from 'three';
import * as xb from 'xrblocks';

import {type SoundEngine} from '../launcher/soundEngine';
import {type AppDefinition, type WindowPose} from '../launcher/types';
import {SpatialWindow, type SpatialWindowCallbacks} from './SpatialWindow';

type FinishId = 'titanium' | 'obsidian' | 'ceramic';
type ModelPresetId = 'headset' | 'orrery';

interface FinishSpec {
  id: FinishId;
  label: string;
  bodyColor: number;
  roughness: number;
  metalness: number;
  trimColor: number;
}

const FINISHES: Record<FinishId, FinishSpec> = {
  titanium: {
    id: 'titanium',
    label: 'Titanium',
    bodyColor: 0xcbd5e1,
    roughness: 0.28,
    metalness: 0.88,
    trimColor: 0x64748b,
  },
  obsidian: {
    id: 'obsidian',
    label: 'Obsidian',
    bodyColor: 0x1e293b,
    roughness: 0.35,
    metalness: 0.72,
    trimColor: 0x38bdf8,
  },
  ceramic: {
    id: 'ceramic',
    label: 'Ceramic',
    bodyColor: 0xf1f5f9,
    roughness: 0.16,
    metalness: 0.12,
    trimColor: 0x94a3b8,
  },
};

/**
 * Builds a composite 8-part procedural Spatial XR Headset (`THREE.Group`)
 * with chassis, anodized frame, curved glass visor, dual optical lenses,
 * bridge arch, temple arms, and braided strap ring.
 */
function buildHeadsetModel(
  bodyMat: THREE.MeshStandardMaterial,
  trimMat: THREE.MeshStandardMaterial,
  visorMat: THREE.MeshPhysicalMaterial,
  lensMat: THREE.MeshStandardMaterial,
  strapMat: THREE.MeshStandardMaterial,
  disposables: Array<{dispose: () => void}>
): THREE.Group {
  const group = new THREE.Group();

  // 1. Main anodized chassis shell (extruded rounded silhouette)
  const shellShape = new THREE.Shape();
  const w = 0.092;
  const h = 0.036;
  const r = 0.018;
  shellShape.moveTo(-w + r, -h);
  shellShape.lineTo(w - r, -h);
  shellShape.quadraticCurveTo(w, -h, w, -h + r);
  shellShape.lineTo(w, h - r);
  shellShape.quadraticCurveTo(w, h, w - r, h);
  shellShape.lineTo(-w + r, h);
  shellShape.quadraticCurveTo(-w, h, -w, h - r);
  shellShape.lineTo(-w, -h + r);
  shellShape.quadraticCurveTo(-w, -h, -w + r, -h);

  const shellGeo = new THREE.ExtrudeGeometry(shellShape, {
    depth: 0.032,
    bevelEnabled: true,
    bevelThickness: 0.006,
    bevelSize: 0.005,
    bevelSegments: 4,
  });
  shellGeo.center();
  const shellMesh = new THREE.Mesh(shellGeo, bodyMat);
  disposables.push(shellGeo);
  group.add(shellMesh);

  // 2. Curved optical front glass visor
  const visorGeo = new THREE.CylinderGeometry(
    0.105,
    0.105,
    0.064,
    32,
    1,
    true,
    -Math.PI * 0.34,
    Math.PI * 0.68
  );
  const visorMesh = new THREE.Mesh(visorGeo, visorMat);
  visorMesh.position.set(0, 0, -0.072);
  disposables.push(visorGeo);
  group.add(visorMesh);

  // 3 & 4. Left & right optical lens barrels
  const lensGeo = new THREE.CylinderGeometry(0.022, 0.024, 0.016, 24);
  lensGeo.rotateX(Math.PI / 2);
  const leftLens = new THREE.Mesh(lensGeo, lensMat);
  leftLens.position.set(-0.038, 0, -0.016);
  const rightLens = new THREE.Mesh(lensGeo, lensMat);
  rightLens.position.set(0.038, 0, -0.016);
  disposables.push(lensGeo);
  group.add(leftLens, rightLens);

  // 5 & 6. Left & right brushed temple arms
  const armGeo = new THREE.BoxGeometry(0.012, 0.022, 0.09);
  const leftArm = new THREE.Mesh(armGeo, trimMat);
  leftArm.position.set(-0.094, 0.002, -0.038);
  const rightArm = new THREE.Mesh(armGeo, trimMat);
  rightArm.position.set(0.094, 0.002, -0.038);
  disposables.push(armGeo);
  group.add(leftArm, rightArm);

  // 7. Top Digital Crown accent
  const crownGeo = new THREE.CylinderGeometry(0.009, 0.009, 0.012, 20);
  const crown = new THREE.Mesh(crownGeo, trimMat);
  crown.position.set(0.068, 0.044, 0.005);
  disposables.push(crownGeo);
  group.add(crown);

  // 8. Soft woven rear headband arc
  const strapGeo = new THREE.TorusGeometry(0.096, 0.011, 12, 36, Math.PI * 1.05);
  strapGeo.rotateX(Math.PI / 2);
  strapGeo.rotateZ(Math.PI * 0.98);
  const strap = new THREE.Mesh(strapGeo, strapMat);
  strap.position.set(0, 0.004, -0.072);
  disposables.push(strapGeo);
  group.add(strap);

  return group;
}

/**
 * Builds a composite 6-part procedural Planetary Orrery (`THREE.Group`).
 */
function buildOrreryModel(
  bodyMat: THREE.MeshStandardMaterial,
  trimMat: THREE.MeshStandardMaterial,
  lensMat: THREE.MeshStandardMaterial,
  disposables: Array<{dispose: () => void}>
): {group: THREE.Group; planets: THREE.Object3D[]} {
  const group = new THREE.Group();
  const planets: THREE.Object3D[] = [];

  // 1. Sun core
  const sunGeo = new THREE.SphereGeometry(0.032, 24, 24);
  const sunMat = new THREE.MeshStandardMaterial({
    color: 0xfbbf24,
    emissive: 0xf59e0b,
    emissiveIntensity: 0.85,
    roughness: 0.25,
  });
  const sun = new THREE.Mesh(sunGeo, sunMat);
  disposables.push(sunGeo, sunMat);
  group.add(sun);

  // 2-4. Three tilted orbital rings + planets
  const orbits = [
    {r: 0.064, tilt: 0.2, size: 0.011, mat: trimMat},
    {r: 0.092, tilt: -0.32, size: 0.015, mat: lensMat},
    {r: 0.118, tilt: 0.14, size: 0.013, mat: bodyMat},
  ];
  orbits.forEach((orb, idx) => {
    const ringGeo = new THREE.TorusGeometry(orb.r, 0.0022, 10, 48);
    ringGeo.rotateX(Math.PI / 2);
    const pivot = new THREE.Group();
    pivot.rotation.z = orb.tilt;
    pivot.rotation.y = idx * 1.4;
    const ring = new THREE.Mesh(ringGeo, trimMat);
    const planetGeo = new THREE.SphereGeometry(orb.size, 18, 18);
    const planet = new THREE.Mesh(planetGeo, orb.mat);
    planet.position.set(orb.r, 0, 0);
    pivot.add(ring, planet);
    group.add(pivot);
    planets.push(pivot);
    disposables.push(ringGeo, planetGeo);
  });

  // 5. Base pedestal ring
  const baseGeo = new THREE.CylinderGeometry(0.036, 0.048, 0.014, 28);
  const base = new THREE.Mesh(baseGeo, bodyMat);
  base.position.y = -0.075;
  disposables.push(baseGeo);
  group.add(base);

  return {group, planets};
}

export function createStudio3DWindow(
  app: AppDefinition,
  pose: WindowPose,
  callbacks: SpatialWindowCallbacks,
  sound: SoundEngine
): SpatialWindow {
  const disposables: Array<{dispose: () => void}> = [];

  const bodyMat = new THREE.MeshStandardMaterial({
    color: FINISHES.titanium.bodyColor,
    roughness: FINISHES.titanium.roughness,
    metalness: FINISHES.titanium.metalness,
  });
  const trimMat = new THREE.MeshStandardMaterial({
    color: FINISHES.titanium.trimColor,
    roughness: 0.32,
    metalness: 0.85,
  });
  const visorMat = new THREE.MeshPhysicalMaterial({
    color: 0x0f172a,
    roughness: 0.06,
    metalness: 0.15,
    clearcoat: 1.0,
    transparent: true,
    opacity: 0.82,
  });
  const lensMat = new THREE.MeshStandardMaterial({
    color: 0x38bdf8,
    emissive: 0x0284c7,
    emissiveIntensity: 0.35,
    roughness: 0.15,
    metalness: 0.8,
  });
  const strapMat = new THREE.MeshStandardMaterial({
    color: 0x475569,
    roughness: 0.88,
    metalness: 0.05,
  });
  disposables.push(bodyMat, trimMat, visorMat, lensMat, strapMat);

  const headsetGroup = buildHeadsetModel(
    bodyMat,
    trimMat,
    visorMat,
    lensMat,
    strapMat,
    disposables
  );
  const orrery = buildOrreryModel(bodyMat, trimMat, lensMat, disposables);
  orrery.group.visible = false;

  const showcaseRoot = new THREE.Group();
  showcaseRoot.add(headsetGroup, orrery.group);

  const modelViewer = new xb.ModelViewer({
    origin: 'center',
    manipulation: {
      actions: {
        rotate: {axis: 'y'},
        scale: {minScale: 0.7, maxScale: 1.8},
      },
      handle: {action: xb.ManipulationAction.Rotate},
    },
  });
  modelViewer.setContent(showcaseRoot);
  modelViewer.position.set(0, 0.23, 0.08);

  let autoSpin = true;
  let activeModel: ModelPresetId = 'headset';
  let activeFinish: FinishId = 'titanium';

  const statusText = new xb.UIText({
    text: 'Model: Spatial Headset · Titanium · Pinch/drag object to rotate',
    style: {fontSize: 13, opacity: 0.78, textAlign: 'center'},
  });

  const finishButtons: Record<FinishId, xb.UIButton> = {} as Record<
    FinishId,
    xb.UIButton
  >;
  const modelButtons: Record<ModelPresetId, xb.UIButton> = {} as Record<
    ModelPresetId,
    xb.UIButton
  >;

  const syncLabels = () => {
    const modelName =
      activeModel === 'headset' ? 'Spatial Headset' : 'Planetary Orrery';
    statusText.text = `Model: ${modelName} · ${FINISHES[activeFinish].label}`;
    (Object.keys(finishButtons) as FinishId[]).forEach((f) => {
      finishButtons[f].style.backgroundColor =
        f === activeFinish
          ? 'rgba(139, 92, 246, 0.65)'
          : 'rgba(255, 255, 255, 0.08)';
    });
    (Object.keys(modelButtons) as ModelPresetId[]).forEach((m) => {
      modelButtons[m].style.backgroundColor =
        m === activeModel
          ? 'rgba(59, 130, 246, 0.65)'
          : 'rgba(255, 255, 255, 0.08)';
    });
  };

  const applyFinish = (id: FinishId) => {
    activeFinish = id;
    const spec = FINISHES[id];
    bodyMat.color.setHex(spec.bodyColor);
    bodyMat.roughness = spec.roughness;
    bodyMat.metalness = spec.metalness;
    trimMat.color.setHex(spec.trimColor);
    syncLabels();
  };

  const selectModel = (id: ModelPresetId) => {
    activeModel = id;
    headsetGroup.visible = id === 'headset';
    orrery.group.visible = id === 'orrery';
    syncLabels();
  };

  const modelRow = new xb.UIPanel({
    style: {width: '100%', flexDirection: 'row', gap: 8},
    children: (['headset', 'orrery'] as ModelPresetId[]).map((m) => {
      const btn = new xb.UIButton({
        label: m === 'headset' ? 'XR Headset' : 'Orrery',
        onClick: () => {
          sound.playTap();
          selectModel(m);
        },
        style: {flexGrow: 1, height: 36, borderRadius: 18, fontSize: 13},
      });
      modelButtons[m] = btn;
      return btn;
    }),
  });

  const finishRow = new xb.UIPanel({
    style: {width: '100%', flexDirection: 'row', gap: 8},
    children: (Object.keys(FINISHES) as FinishId[]).map((f) => {
      const btn = new xb.UIButton({
        label: FINISHES[f].label,
        onClick: () => {
          sound.playTap();
          applyFinish(f);
        },
        style: {flexGrow: 1, height: 36, borderRadius: 18, fontSize: 13},
      });
      finishButtons[f] = btn;
      return btn;
    }),
  });

  const spinButton = new xb.UIButton({
    label: 'Auto-Spin: On',
    icon: '360',
    onClick: () => {
      sound.playTap();
      autoSpin = !autoSpin;
      spinButton.label = autoSpin ? 'Auto-Spin: On' : 'Auto-Spin: Off';
    },
    style: {width: '100%', height: 38, borderRadius: 19, fontSize: 13},
  });

  syncLabels();

  const win = new SpatialWindow(app, pose, callbacks, [
    statusText,
    modelRow,
    finishRow,
    spinButton,
  ]);
  win.registerAttachment(modelViewer, new THREE.Vector3(0, 0.28, 0.08), false);
  win.registerTicker((dt) => {
    if (autoSpin) {
      showcaseRoot.rotation.y += dt * 0.45;
    }
    if (orrery.group.visible) {
      orrery.planets.forEach((p, idx) => {
        p.rotation.y += dt * (0.65 + idx * 0.35);
      });
    }
  });
  win.registerCleanup(() => {
    for (const d of disposables) {
      d.dispose();
    }
  });
  return win;
}
