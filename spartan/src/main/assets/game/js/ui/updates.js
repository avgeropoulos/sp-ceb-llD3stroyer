/**
 * The "Update available" banner (DESIGN.md §2.2). The Android host checks the GitHub release for a
 * newer APK at launch; when there is one, this offers it on the title screen (never mid-fight).
 * Update downloads it and opens Android's installer, where the player confirms; saved progress is
 * kept because every build is signed with the same key. On the web it stays hidden.
 */

/**
 * What the banner says for an updater state, or null to hide it.
 * @param {{state:string, name?:string, progress?:number, error?:string}} st
 * @returns {{title:string, text:string, action:string|null, progress:number|null}|null}
 */
export function updateView(st) {
  if (!st || !st.enabled) return null;
  const name = st.name ? ` ${st.name}` : '';
  switch (st.state) {
    case 'available':
      return { title: 'Update available', text: `Version${name} is ready. Your progress is kept.`, action: 'Update', progress: null };
    case 'downloading': {
      const p = Math.max(0, Math.min(1, Number(st.progress) || 0));
      return { title: 'Downloading update…', text: `${Math.round(p * 100)}%`, action: null, progress: p };
    }
    case 'ready':
      return { title: 'Update downloaded', text: 'Tap Install, then confirm in Android’s installer.', action: 'Install', progress: null };
    case 'permission':
      return {
        title: 'One more step',
        text: 'Android asks once: turn on “Allow from this source” for Thermopylae, then come back.',
        action: 'Try again',
        progress: null,
      };
    case 'error':
      return { title: 'Update failed', text: st.error || 'Something went wrong.', action: 'Retry', progress: null };
    default:
      return null;
  }
}

/**
 * Creates the banner.
 * @param {object} ctx shared context (§4)
 */
export function createUpdates(ctx) {
  const updates = ctx.platform && ctx.platform.updates;
  if (!updates) return { refresh() {} };

  const root = document.createElement('div');
  root.id = 'app-update';
  root.setAttribute('role', 'status');
  root.setAttribute('aria-live', 'polite');
  root.innerHTML =
    '<div class="upd-body"><div class="upd-h"></div><div class="upd-t"></div>' +
    '<div class="upd-bar"><i></i></div></div>' +
    '<div class="upd-btns"><button type="button" class="upd-btn upd-later">Later</button>' +
    '<button type="button" class="upd-btn upd-go"></button></div>';
  // It lives in the title screen, between the logo and the buttons, so it shows and fades with it.
  const title = ctx.overlay.querySelector('#menus [data-screen="title"]');
  const bar = title && title.querySelector('.mn-title-bar');
  if (bar) title.insertBefore(root, bar);
  else ctx.overlay.appendChild(root);
  const h = root.querySelector('.upd-h');
  const t = root.querySelector('.upd-t');
  const meter = root.querySelector('.upd-bar');
  const fill = meter.querySelector('i');
  const go = root.querySelector('.upd-go');
  const later = root.querySelector('.upd-later');

  let st = updates.state();
  let dismissed = '';

  const key = () => `${st.state}:${st.available}`;

  function render() {
    const view = updateView(st);
    const show = !!view && dismissed !== key();
    root.classList.toggle('is-on', show);
    if (!view) return;
    // textContent only: the notes and error text come from outside the game.
    h.textContent = view.title;
    t.textContent = view.text;
    meter.style.display = view.progress === null ? 'none' : '';
    fill.style.width = `${Math.round((view.progress || 0) * 100)}%`;
    go.style.display = view.action ? '' : 'none';
    go.textContent = view.action || '';
  }

  const press = (e) => {
    e.preventDefault();
    e.stopPropagation();
  };
  go.addEventListener('click', (e) => {
    press(e);
    dismissed = '';
    if (st.state === 'error' || st.state === 'available' || st.state === 'ready' || st.state === 'permission') updates.install();
  });
  later.addEventListener('click', (e) => {
    press(e);
    dismissed = key();
    render();
  });

  updates.onChange((next) => {
    st = next;
    render();
  });
  render();

  return {
    /** Re-reads the state from the host (e.g. after a test changes it). */
    refresh() {
      st = updates.state();
      render();
    },
  };
}
