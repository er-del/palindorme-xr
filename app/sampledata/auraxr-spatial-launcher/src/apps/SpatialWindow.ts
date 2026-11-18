import * as THREE from 'three';
import * as xb from 'xrblocks';

import {type AppDefinition, type WindowPose} from '../launcher/types';

export interface SpatialWindowCallbacks {
  onClose: () => void;
  onTogglePin: () => boolean;
}

interface WindowAttachment {
  object: THREE.Object3D;
  localOffset: THREE.Vector3;
  syncRotation: boolean;
}

/**
 * VisionOS-inspired floating spatial window root (`xb.UICard`) with
 * built-in manipulation, edge drag bar, head-pin toggle, and close button.
 */
export class SpatialWindow extends xb.UICard {
  readonly app: AppDefinition;
  private pinButton: xb.UIButton;
  private pinIcon: xb.UIIcon;
  private followScript: xb.FollowHead | null = null;
  private cleanupCallbacks: Array<() => void> = [];
  private tickers: Array<(dt: number) => void> = [];
  private attachments: WindowAttachment[] = [];
  private tempOffset = new THREE.Vector3();

  constructor(
    app: AppDefinition,
    pose: WindowPose,
    callbacks: SpatialWindowCallbacks,
    bodyChildren: xb.UIElement[]
  ) {
    const pinIcon = new xb.UIIcon({
      icon: 'keep',
      pointerEvents: 'none',
      style: {width: 20, height: 20, color: '#ffffff'},
    });
    const pinButton = new xb.UIButton({
      ariaLabel: `Pin ${app.name} to view`,
      onClick: () => {
        const pinned = callbacks.onTogglePin();
        this.setPinnedToHead(pinned);
      },
      style: {
        width: 38,
        height: 38,
        borderRadius: 19,
        paddingLeft: 0,
        paddingRight: 0,
        justifyContent: 'center',
        alignItems: 'center',
        backgroundColor: 'rgba(255, 255, 255, 0.08)',
      },
      children: [pinIcon],
    });

    const closeButton = new xb.UIButton({
      ariaLabel: `Close ${app.name}`,
      onClick: callbacks.onClose,
      style: {
        width: 38,
        height: 38,
        borderRadius: 19,
        paddingLeft: 0,
        paddingRight: 0,
        justifyContent: 'center',
        alignItems: 'center',
        backgroundColor: 'rgba(255, 255, 255, 0.08)',
        ':hover': {backgroundColor: 'rgba(239, 68, 68, 0.65)'},
      },
      children: [
        new xb.UIIcon({
          icon: 'close',
          pointerEvents: 'none',
          style: {width: 20, height: 20, color: '#ffffff'},
        }),
      ],
    });

    const titleBar = new xb.UIPanel({
      style: {
        width: '100%',
        flexDirection: 'row',
        justifyContent: 'space-between',
        alignItems: 'center',
        paddingBottom: 4,
        flexShrink: 0,
      },
      children: [
        new xb.UIPanel({
          style: {
            flexDirection: 'row',
            alignItems: 'center',
            gap: 10,
          },
          children: [
            new xb.UIPanel({
              style: {
                width: 34,
                height: 34,
                borderRadius: 17,
                backgroundColor: app.accentColor,
                justifyContent: 'center',
                alignItems: 'center',
              },
              children: [
                new xb.UIIcon({
                  icon: app.icon,
                  pointerEvents: 'none',
                  style: {width: 20, height: 20, color: '#ffffff'},
                }),
              ],
            }),
            new xb.UIPanel({
              style: {
                flexDirection: 'column',
                alignItems: 'flex-start',
                gap: 2,
              },
              children: [
                new xb.UIText({
                  text: app.name,
                  style: {fontSize: 20, fontWeight: 'bold'},
                }),
                new xb.UIText({
                  text: app.subtitle,
                  style: {fontSize: 12, opacity: 0.68},
                }),
              ],
            }),
          ],
        }),
        new xb.UIPanel({
          style: {flexDirection: 'row', alignItems: 'center', gap: 8},
          children: [pinButton, closeButton],
        }),
      ],
    });

    const divider = new xb.UIPanel({
      pointerEvents: 'none',
      style: {
        width: '100%',
        height: 1,
        flexShrink: 0,
        backgroundColor: 'rgba(255, 255, 255, 0.14)',
      },
    });

    super({
      size: app.defaultSize,
      manipulation: true,
      edge: true,
      style: {
        flexDirection: 'column',
        justifyContent: 'flex-start',
        gap: 14,
        padding: 20,
      },
      children: [titleBar, divider, ...bodyChildren],
    });

    this.name = `${app.name} window`;
    this.app = app;
    this.pinButton = pinButton;
    this.pinIcon = pinIcon;
    this.setPose(pose);
  }

  setPose(pose: WindowPose): void {
    const wasPinned = Boolean(this.followScript);
    if (wasPinned) {
      this.setPinnedToHead(false);
    }
    this.position.set(pose.x, pose.y, pose.z);
    this.rotation.set(0, pose.rotY, 0);
    this.syncAttachments();
  }

  setPinnedToHead(pinned: boolean): void {
    if (pinned && !this.followScript) {
      const cam = xb.core.camera;
      const offset = this.position.clone();
      if (cam) {
        cam.updateMatrixWorld();
        offset.copy(this.position);
        cam.worldToLocal(offset);
      }
      this.followScript = new xb.FollowHead({
        offset: new THREE.Vector3(
          THREE.MathUtils.clamp(offset.x, -0.8, 0.8),
          THREE.MathUtils.clamp(offset.y, -0.35, 0.35),
          THREE.MathUtils.clamp(offset.z, -1.6, -0.8)
        ),
        smoothing: 0.1,
      });
      this.add(this.followScript);
    } else if (!pinned && this.followScript) {
      this.followScript.removeFromParent();
      this.followScript = null;
    }

    this.pinButton.style.backgroundColor = pinned
      ? '#3b82f6'
      : 'rgba(255, 255, 255, 0.08)';
    this.pinIcon.filled = pinned;
  }

  registerTicker(fn: (dt: number) => void): void {
    this.tickers.push(fn);
  }

  registerAttachment(
    object: THREE.Object3D,
    localOffset: THREE.Vector3,
    syncRotation = true
  ): void {
    this.attachments.push({
      object,
      localOffset: localOffset.clone(),
      syncRotation,
    });
    this.syncAttachments();
  }

  getAttachments(): THREE.Object3D[] {
    return this.attachments.map((att) => att.object);
  }

  private syncAttachments(): void {
    for (const att of this.attachments) {
      this.tempOffset
        .copy(att.localOffset)
        .applyQuaternion(this.quaternion)
        .add(this.position);
      att.object.position.copy(this.tempOffset);
      if (att.syncRotation) {
        att.object.quaternion.copy(this.quaternion);
      }
    }
  }

  tick(dt: number): void {
    this.syncAttachments();
    for (const fn of this.tickers) {
      fn(dt);
    }
  }

  registerCleanup(fn: () => void): void {
    this.cleanupCallbacks.push(fn);
  }

  disposeWindow(): void {
    if (this.followScript) {
      this.followScript.removeFromParent();
      this.followScript = null;
    }
    for (const att of this.attachments) {
      att.object.removeFromParent();
    }
    this.attachments = [];
    this.tickers = [];
    for (const fn of this.cleanupCallbacks) {
      fn();
    }
    this.cleanupCallbacks = [];
    this.removeFromParent();
  }
}
