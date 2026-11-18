import * as xb from 'xrblocks';

import {DEFAULT_NOTES} from '../launcher/launcherState';
import {type SoundEngine} from '../launcher/soundEngine';
import {type AppDefinition, type NoteItem, type WindowPose} from '../launcher/types';
import {SpatialWindow, type SpatialWindowCallbacks} from './SpatialWindow';

const QUICK_IDEAS: readonly {title: string; body: string}[] = [
  {
    title: 'Desk Layout Preset',
    body: 'Keep Browser left, Notes center, and Music on the right arc.',
  },
  {
    title: 'VR Cinema Check',
    body: 'Test Midnight Studio at 100% immersion for movie viewing.',
  },
  {
    title: 'Android Bridge Sync',
    body: 'Verify ADB Shell & Files views show installed spatial packages.',
  },
];

export function createNotesWindow(
  app: AppDefinition,
  pose: WindowPose,
  callbacks: SpatialWindowCallbacks,
  sound: SoundEngine
): SpatialWindow {
  let notes: NoteItem[] = DEFAULT_NOTES.map((n) => ({...n}));
  let quickIdeaIdx = 0;

  const listView = new xb.UIScrollView({
    ariaLabel: 'Spatial notes list',
    style: {flexGrow: 1, flexBasis: 0, minHeight: 170, gap: 10},
  });

  const countText = new xb.UIText({
    text: '',
    style: {fontSize: 13, opacity: 0.72},
  });

  const renderNotes = () => {
    listView.remove(...listView.children);
    const remaining = notes.filter((n) => !n.done).length;
    countText.text = `${notes.length} notes · ${remaining} active`;

    notes.forEach((note) => {
      const checkIcon = new xb.UIIcon({
        icon: note.done ? 'check_circle' : 'radio_button_unchecked',
        filled: note.done,
        pointerEvents: 'none',
        style: {
          width: 22,
          height: 22,
          color: note.done ? '#34d399' : '#f59e0b',
        },
      });
      const toggleBtn = new xb.UIButton({
        ariaLabel: `Toggle ${note.title}`,
        onClick: () => {
          sound.playTap();
          note.done = !note.done;
          renderNotes();
        },
        style: {
          width: 34,
          height: 34,
          borderRadius: 17,
          paddingLeft: 0,
          paddingRight: 0,
          backgroundColor: 'transparent',
          borderWidth: 0,
          justifyContent: 'center',
          alignItems: 'center',
        },
        children: [checkIcon],
      });

      const row = new xb.UIPanel({
        style: {
          width: '100%',
          flexDirection: 'row',
          alignItems: 'flex-start',
          gap: 10,
          padding: 12,
          borderRadius: 14,
          backgroundColor: note.done
            ? 'rgba(255, 255, 255, 0.04)'
            : 'rgba(255, 255, 255, 0.09)',
        },
        children: [
          toggleBtn,
          new xb.UIPanel({
            style: {
              flexGrow: 1,
              flexDirection: 'column',
              alignItems: 'flex-start',
              gap: 3,
            },
            children: [
              new xb.UIText({
                text: note.title,
                style: {
                  fontSize: 15,
                  fontWeight: 'bold',
                  opacity: note.done ? 0.55 : 1,
                },
              }),
              new xb.UIText({
                text: note.body,
                style: {
                  fontSize: 13,
                  lineHeight: 1.3,
                  opacity: note.done ? 0.45 : 0.78,
                },
              }),
            ],
          }),
        ],
      });
      listView.add(row);
    });
  };

  const toolbar = new xb.UIPanel({
    style: {
      width: '100%',
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'space-between',
      gap: 8,
    },
    children: [
      countText,
      new xb.UIPanel({
        style: {flexDirection: 'row', gap: 8},
        children: [
          new xb.UIButton({
            label: 'Add Note',
            icon: 'add',
            onClick: () => {
              sound.playTap();
              const idea = QUICK_IDEAS[quickIdeaIdx % QUICK_IDEAS.length];
              quickIdeaIdx++;
              notes.unshift({
                id: `note-${Date.now()}`,
                title: idea.title,
                body: idea.body,
                done: false,
              });
              renderNotes();
            },
            style: {height: 36, borderRadius: 18, fontSize: 13},
          }),
          new xb.UIButton({
            label: 'Clear Done',
            onClick: () => {
              sound.playTap();
              notes = notes.filter((n) => !n.done);
              renderNotes();
            },
            style: {height: 36, borderRadius: 18, fontSize: 13},
          }),
        ],
      }),
    ],
  });

  renderNotes();
  return new SpatialWindow(app, pose, callbacks, [toolbar, listView]);
}
