import * as THREE from 'three';
import * as xb from 'xrblocks';

import {createPhotoGalleryTextures} from '../launcher/proceduralTextures';
import {type SoundEngine} from '../launcher/soundEngine';
import {type AppDefinition, type WindowPose} from '../launcher/types';
import {SpatialWindow, type SpatialWindowCallbacks} from './SpatialWindow';

export function createPhotosWindow(
  app: AppDefinition,
  pose: WindowPose,
  callbacks: SpatialWindowCallbacks,
  sound: SoundEngine
): SpatialWindow {
  const photos = createPhotoGalleryTextures();
  let currentIndex = 0;
  let spatialDepthActive = false;

  const previewImage = new xb.UIImage({
    src: photos[0].texture,
    ariaLabel: 'Spatial photo preview',
    pointerEvents: 'none',
    style: {
      position: 'absolute',
      top: 0,
      right: 0,
      bottom: 0,
      left: 0,
      width: '100%',
      height: '100%',
      objectFit: 'cover',
      borderRadius: 16,
    },
  });
  const previewFrame = new xb.UIPanel({
    style: {
      position: 'relative',
      width: '100%',
      height: 205,
      overflow: 'hidden',
      borderRadius: 16,
      backgroundColor: '#0b101b',
      flexShrink: 0,
    },
    children: [previewImage],
  });

  const titleText = new xb.UIText({
    text: photos[0].title,
    style: {fontSize: 20, fontWeight: 'bold'},
  });
  const locationText = new xb.UIText({
    text: photos[0].location,
    style: {fontSize: 13, opacity: 0.72},
  });

  // 3D floating spatial depth layer that pops forward slightly in Z
  const popOutGeo = new THREE.PlaneGeometry(0.46, 0.258);
  const popOutMat = new THREE.MeshBasicMaterial({
    map: photos[0].texture,
    transparent: true,
    opacity: 0.96,
  });
  const popOutMesh = new THREE.Mesh(popOutGeo, popOutMat);
  popOutMesh.position.set(0, 0.02, 0.09);
  popOutMesh.visible = false;
  (popOutMesh as THREE.Object3D & {xb?: xb.XBObjectOptions}).xb = {
    pointerEvents: 'none',
  };

  const spatialToggle = new xb.UIButton({
    label: 'Spatial Depth',
    icon: 'layers',
    onClick: () => {
      sound.playTap();
      spatialDepthActive = !spatialDepthActive;
      popOutMesh.visible = spatialDepthActive;
      spatialToggle.style.backgroundColor = spatialDepthActive
        ? 'rgba(236, 72, 153, 0.65)'
        : 'rgba(255, 255, 255, 0.08)';
    },
    style: {height: 38, borderRadius: 19},
  });

  const selectPhoto = (index: number) => {
    currentIndex = (index + photos.length) % photos.length;
    const item = photos[currentIndex];
    previewImage.src = item.texture;
    titleText.text = item.title;
    locationText.text = item.location;
    popOutMat.map = item.texture;
    popOutMat.needsUpdate = true;
  };

  const controlsRow = new xb.UIPanel({
    style: {
      width: '100%',
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: 10,
      flexShrink: 0,
    },
    children: [
      new xb.UIButton({
        icon: 'chevron_left',
        ariaLabel: 'Previous photo',
        onClick: () => {
          sound.playTap();
          selectPhoto(currentIndex - 1);
        },
        style: {width: 44, height: 38, borderRadius: 19, paddingLeft: 0, paddingRight: 0},
      }),
      spatialToggle,
      new xb.UIButton({
        icon: 'chevron_right',
        ariaLabel: 'Next photo',
        onClick: () => {
          sound.playTap();
          selectPhoto(currentIndex + 1);
        },
        style: {width: 44, height: 38, borderRadius: 19, paddingLeft: 0, paddingRight: 0},
      }),
    ],
  });

  const metaRow = new xb.UIPanel({
    style: {
      width: '100%',
      flexDirection: 'column',
      alignItems: 'flex-start',
      gap: 2,
      flexShrink: 0,
    },
    children: [titleText, locationText],
  });

  const thumbsRow = new xb.UIPanel({
    style: {width: '100%', flexDirection: 'row', gap: 8, flexShrink: 0},
    children: photos.map(
      (p, idx) =>
        new xb.UIButton({
          ariaLabel: `View ${p.title}`,
          onClick: () => {
            sound.playTap();
            selectPhoto(idx);
          },
          style: {
            position: 'relative',
            flexGrow: 1,
            flexBasis: 0,
            height: 64,
            padding: 0,
            overflow: 'hidden',
            borderRadius: 10,
            backgroundColor: 'rgba(255, 255, 255, 0.08)',
          },
          children: [
            new xb.UIImage({
              src: p.texture,
              ariaLabel: p.title,
              pointerEvents: 'none',
              style: {
                width: '100%',
                height: '100%',
                objectFit: 'fill',
                borderRadius: 10,
              },
            }),
          ],
        })
    ),
  });

  const win = new SpatialWindow(app, pose, callbacks, [
    previewFrame,
    metaRow,
    controlsRow,
    thumbsRow,
  ]);
  win.registerAttachment(popOutMesh, new THREE.Vector3(0, 0.03, 0.09), true);
  win.registerCleanup(() => {
    popOutGeo.dispose();
    popOutMat.dispose();
    for (const p of photos) {
      p.texture.dispose();
    }
  });
  return win;
}
