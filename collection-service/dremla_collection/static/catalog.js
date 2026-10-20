'use strict';
const $ = id => document.getElementById(id);
let state;
function el(tag, text, cls) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (cls) node.className = cls;
  return node;
}
function error(message) { $('error').textContent = message; $('error').hidden = !message; }
async function api(path = '', method = 'GET', body) {
  const response = await fetch('/api/catalog' + path, {method, headers: method === 'GET' ? {} :
    {'Content-Type': 'application/json', 'X-Dremla-Request': '1'}, body: body === undefined ? undefined : JSON.stringify(body)});
  const value = await response.json();
  if (!response.ok) throw Object.assign(new Error(value.message || 'Request failed.'), {code: value.error});
  return value;
}
// Outdated review, another tab's completion, or an interrupted write: show the server's state before a retry.
const STALE = new Set(['stale-draft', 'stale-parent', 'stale-pending', 'pending-publication', 'not-found', 'local-failure']);
async function action(fn) {
  try { error(''); await fn(); await refresh(); } catch (e) {
    if (STALE.has(e.code)) await refresh().catch(() => {});
    error(e.message);
  }
}
function button(text, fn) {
  const node = el('button', text, 'secondary'); node.type = 'button';
  node.onclick = () => action(fn); return node;
}
function input(parent, label, value, type = 'text') {
  const wrapper = el('label', label + ' '), node = el('input');
  node.type = type; node.value = value; wrapper.append(node); parent.append(wrapper); return node;
}
function category(parent, value) {
  const wrapper = el('label', 'Category '), node = el('select');
  for (const c of ['ambient', 'piano']) node.add(new Option(c, c));
  node.value = value; wrapper.append(node); parent.append(wrapper); return node;
}
function drawTracks() {
  const root = $('tracks'), draft = state.draft; root.replaceChildren();
  if (!draft.tracks.length) root.append(el('p', 'Your catalog is empty. Add a track, then create a playlist.', 'muted'));
  for (const track of draft.tracks) {
    const card = el('div', undefined, 'card'), form = el('form');
    card.append(el('h3', track.title), el('small', track.id));
    const title = input(form, 'Title', track.title), artist = input(form, 'Artist', track.artist);
    title.required = artist.required = true;
    const duration = input(form, 'Duration in seconds', track.durationSec, 'number'); duration.min = '1'; duration.step = '1'; duration.required = true;
    const cat = category(form, track.category), gain = input(form, 'Manual gain in dB (blank clears)', track.gainDb ?? '', 'number');
    gain.min = '-40'; gain.max = '0'; gain.step = 'any';
    const save = el('button', 'Save track'); form.append(save);
    form.onsubmit = event => { event.preventDefault(); action(() => api('/tracks/' + track.id, 'PATCH', {
      title: title.value, artist: artist.value, durationSec: Number(duration.value), category: cat.value,
      gainDb: gain.value === '' ? null : Number(gain.value)
    })); };
    const label = el('label', undefined, 'row'), exclude = el('input'); exclude.type = 'checkbox'; exclude.checked = draft.excluded.includes(track.id);
    exclude.onchange = () => action(async () => {
      try {
        const ids = draft.excluded.filter(id => id !== track.id); if (exclude.checked) ids.push(track.id);
        await api('/excluded', 'PUT', {excluded: ids});
      } catch (e) { exclude.checked = !exclude.checked; throw e; }
    });
    label.append(exclude, document.createTextNode('Exclude from every playlist'));
    card.append(form, label, button('Remove track', async () => {
      if (confirm(`Remove “${track.title}” from the catalog and all playlists?`)) await api('/tracks/' + track.id, 'DELETE', {});
    })); root.append(card);
  }
}
function drawPlaylists() {
  const root = $('playlists'), draft = state.draft; root.replaceChildren();
  if (!draft.playlists.length) root.append(el('p', 'No playlists yet.', 'muted'));
  for (const playlist of draft.playlists) {
    const card = el('div', undefined, 'card'), form = el('form');
    const save = changes => api('/playlists/' + playlist.id, 'PATCH', changes);
    card.append(el('h3', playlist.title), el('small', playlist.id));
    const title = input(form, 'Title', playlist.title); title.required = true; form.append(el('button', 'Rename'));
    form.onsubmit = event => { event.preventDefault(); action(() => save({title: title.value})); }; card.append(form);
    const list = el('ol');
    playlist.trackIds.forEach((id, index) => {
      const row = el('li'), track = draft.tracks.find(t => t.id === id);
      row.append(el('span', track.title + (draft.excluded.includes(id) ? ' · excluded ' : ' ')));
      const move = delta => {
        const ids = [...playlist.trackIds]; [ids[index], ids[index + delta]] = [ids[index + delta], ids[index]];
        return save({trackIds: ids});
      };
      const up = button('Up', () => move(-1)), down = button('Down', () => move(1));
      up.disabled = index === 0; down.disabled = index === playlist.trackIds.length - 1;
      row.append(up, document.createTextNode(' '), down, document.createTextNode(' '),
        button('Remove', () => save({trackIds: playlist.trackIds.filter(t => t !== id)}))); list.append(row);
    }); card.append(list);
    const wrapper = el('label', 'Add track '), picker = el('select');
    for (const t of draft.tracks.filter(t => !playlist.trackIds.includes(t.id))) picker.add(new Option(t.title, t.id));
    wrapper.append(picker); card.append(wrapper);
    const add = button('Add to playlist', () => save({trackIds: [...playlist.trackIds, picker.value]})); add.disabled = !picker.options.length;
    card.append(add, document.createTextNode(' '), button('Delete playlist', async () => {
      if (confirm(`Delete “${playlist.title}”?`)) await api('/playlists/' + playlist.id, 'DELETE', {});
    })); root.append(card);
  }
}
async function refresh() {
  state = await api(); drawTracks(); drawPlaylists();
  $('active').replaceChildren(new Option('Choose a playlist', ''));
  for (const p of state.draft.playlists) $('active').add(new Option(p.title, p.id));
  $('active').value = state.draft.active || '';
  $('validation').replaceChildren(...(state.validation.errors.length ? state.validation.errors : ['Ready to publish.']).map(message => el('p', message)));
  $('selection').replaceChildren(...state.selection.map(id => el('li', state.draft.tracks.find(t => t.id === id).title)));
  $('publish').textContent = `Publish catalog version ${state.currentVersion} → ${state.currentVersion + 1}`;
  $('publish').disabled = !state.validation.valid;
  const pending = state.pending;
  $('pending').hidden = !pending;
  if (pending) {
    $('pending-summary').textContent = `Version ${pending.version} was saved at ${pending.generated} by an interrupted ` +
      `publication but never became current; ` +
      (state.currentVersion ? `clients still receive version ${state.currentVersion}. ` : 'no version is current yet. ') +
      `It is kept unchanged. Make it current, then publish newer draft changes as the next version. ` +
      `Tracks: ${pending.counts.tracks} · playlists: ${pending.counts.playlists} · ` +
      (pending.matchesDraft ? 'same content as the current draft.' : 'differs from the current draft.');
    $('pending-selection').replaceChildren(...pending.selection.map(t => el('li', `${t.title} (${t.id})`)));
    $('pending-document').textContent = JSON.stringify(pending.document, null, 2);
    $('complete-pending').textContent = `Make version ${pending.version} current`;
  }
  $('fixtures').replaceChildren(...state.fixtures.map(t => el('p', `${t.title}: ${t.source.url}`)));
  const history = await api('/versions'); $('history').replaceChildren();
  if (!history.length) $('history').append(el('p', 'No published catalog versions.', 'muted'));
  for (const v of history) {
    const row = el('p'), link = el('a', `Version ${v.version}`); link.href = `/catalog/v${v.version}.json`;
    row.append(link, document.createTextNode(` · ${v.generated} · ${v.counts.tracks} tracks · ${v.counts.selection} selected`)); $('history').append(row);
  }
}
$('url').addEventListener('input', () => {
  if (!state) return;
  try {
    const url = new URL($('url').value.trim());
    const video = url.hostname === 'youtu.be' ? url.pathname.slice(1) : url.searchParams.get('v');
    const fixture = state.fixtures.find(t => t.source.videoId === video);
    if (fixture) {
      $('title').value = fixture.title; $('artist').value = fixture.artist;
      $('duration').value = fixture.durationSec; $('category').value = fixture.category;
    }
  } catch (_) { /* The server validates every submitted URL. */ }
});
$('add').onsubmit = event => { event.preventDefault(); action(async () => {
  const body = {url: $('url').value.trim()};
  // Playlist imports use only the fixture catalogue's metadata.
  const playlist = new URL(body.url).pathname === '/playlist';
  if (!playlist) {
    if ($('title').value) body.title = $('title').value;
    if ($('artist').value) body.artist = $('artist').value;
    if ($('duration').value) body.durationSec = Number($('duration').value);
    body.category = $('category').value;
    if ($('gain').value !== '') body.gainDb = Number($('gain').value);
  }
  await api('/tracks', 'POST', body); $('add').reset();
}); };
$('add-playlist').onsubmit = event => { event.preventDefault(); action(async () => {
  await api('/playlists', 'POST', {id: $('playlist-id').value, title: $('playlist-title').value}); $('add-playlist').reset();
}); };
$('active').onchange = () => action(() => api('/active', 'PUT', {active: $('active').value || null}));
$('refresh').onclick = () => action(async () => {});
$('publish').onclick = () => action(async () => {
  // Confirm exactly what is shown: the server refuses if this draft revision is no longer current.
  const shown = state, parent = shown.currentVersion;
  const selected = shown.selection.map(id => { const t = shown.draft.tracks.find(t => t.id === id); return `${t.title} (${id})`; }).join('\n');
  if (confirm(`Publish catalog version ${parent} → ${parent + 1}?\n\nSelected tracks, in order:\n${selected}`)) {
    await api('/publish', 'POST', {parentVersion: parent, draftRevision: shown.draftRevision, confirm: true});
  }
});
$('complete-pending').onclick = () => action(async () => {
  const pending = state.pending;
  const selected = pending.selection.map(t => `${t.title} (${t.id})`).join('\n');
  if (confirm(`Make interrupted version ${pending.version} current?\n\nSelected tracks, in order:\n${selected}`)) {
    await api('/pending/complete', 'POST', {sha256: pending.sha256, confirm: true});
  }
});
action(async () => {});
