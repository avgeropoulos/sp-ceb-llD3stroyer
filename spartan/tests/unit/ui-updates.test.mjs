import test from 'node:test';
import assert from 'node:assert/strict';
import { updateView } from '../../src/main/assets/game/js/ui/updates.js';

test('update banner: hidden unless the host has an updater and something to offer', () => {
  assert.equal(updateView(null), null);
  assert.equal(updateView({ state: 'available', enabled: false }), null, 'web / local builds');
  for (const state of ['idle', 'checking', 'current']) assert.equal(updateView({ state, enabled: true }), null, state);
});

test('update banner: each state says what happens next', () => {
  const avail = updateView({ state: 'available', enabled: true, name: '1.42' });
  assert.equal(avail.action, 'Update');
  assert.match(avail.text, /1\.42/);
  assert.match(avail.text, /progress is kept/);
  const dl = updateView({ state: 'downloading', enabled: true, progress: 0.375 });
  assert.equal(dl.action, null, 'nothing to tap while downloading');
  assert.equal(dl.text, '38%');
  assert.equal(dl.progress, 0.375);
  assert.equal(updateView({ state: 'downloading', enabled: true, progress: 7 }).progress, 1);
  assert.equal(updateView({ state: 'downloading', enabled: true }).text, '0%');
  assert.equal(updateView({ state: 'ready', enabled: true }).action, 'Install');
  assert.match(updateView({ state: 'permission', enabled: true }).text, /Allow from this source/);
  const err = updateView({ state: 'error', enabled: true, error: 'Download failed.' });
  assert.equal(err.action, 'Retry');
  assert.equal(err.text, 'Download failed.');
});
