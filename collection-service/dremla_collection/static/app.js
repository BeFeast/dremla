'use strict';
const $ = id => document.getElementById(id);
const active = new Set(['queued', 'acquiring', 'processing']);
let review, timer, draftId = '', jobsSignature = '', reviewSignature = '';
function el(tag, text, cls) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (cls) node.className = cls;
  return node;
}
function error(message) { $('error').textContent = message; $('error').hidden = !message; }
async function api(path, method = 'GET', body) {
  const response = await fetch('/api/' + path, {method, headers: method === 'GET' ? {} :
    {'Content-Type': 'application/json', 'X-Dremla-Request': '1'}, body: body === undefined ? undefined : JSON.stringify(body)});
  const value = await response.json();
  if (!response.ok) throw new Error(value.message || 'Request failed.');
  return value;
}
async function action(fn) {
  try { error(''); await fn(); await refresh(); } catch (e) { error(e.message); }
}
function button(text, fn, secondary = true) {
  const node = el('button', text, secondary ? 'secondary' : '');
  node.type = 'button'; node.onclick = () => action(fn); return node;
}
function check(text, checked, fn) {
  const label = el('label', undefined, 'row'), input = el('input');
  input.type = 'checkbox'; input.checked = checked;
  input.onchange = () => action(async () => { try { await fn(input.checked); } catch (e) { input.checked = !input.checked; throw e; } });
  label.append(input, document.createTextNode(text)); return label;
}
async function drafts() {
  const list = await api('drafts'), picker = $('draft-picker');
  picker.replaceChildren(new Option('Choose a draft', ''));
  for (const d of list) picker.add(new Option(d.kind + ' · ' + d.items.map(i => i.title).join(', '), d.id));
  picker.value = draftId;
}
async function drawDraft() {
  const root = $('draft'); root.replaceChildren();
  if (!draftId) return;
  const draft = await api('drafts/' + draftId);
  for (const item of draft.items) {
    const card = el('div', undefined, 'card');
    const save = changes => api(`drafts/${draftId}/items/${item.id}`, 'PATCH', changes);
    card.append(check('Include' + (item.inCollection ? ' · in collection' : ''), item.include, value => save({include: value})));
    const titleLabel = el('label', 'Title '), title = el('input'); title.type = 'text'; title.value = item.title; title.maxLength = 300;
    title.onchange = () => action(() => save({title: title.value})); titleLabel.append(title);
    const categoryLabel = el('label', 'Category '), category = el('select');
    for (const c of ['ambient', 'piano']) category.add(new Option(c, c));
    category.value = item.category; category.onchange = () => action(() => save({category: category.value})); categoryLabel.append(category);
    card.append(titleLabel, categoryLabel, el('small', `${item.artist} · ${item.duration_sec} s · ${item.id}`));
    if (item.editLocked) {
      card.querySelectorAll('input, select').forEach(field => { field.disabled = true; });
      card.append(el('p', 'Processing has started. Create a new draft to edit this item.', 'muted'));
    }
    root.append(card);
  }
  root.append(button('Process included tracks', async () => { await api(`drafts/${draftId}/process`, 'POST', {}); await drawDraft(); }, false));
}
function drawJobs(jobs) {
  const sig = JSON.stringify(jobs); if (sig === jobsSignature) return; jobsSignature = sig;
  $('jobs').replaceChildren();
  if (!jobs.length) $('jobs').append(el('p', 'No jobs yet.', 'muted'));
  for (const job of jobs) {
    const card = el('div', undefined, 'card'); card.append(el('p', job.item.title + ' · ' + job.state));
    if (job.error) card.append(el('p', job.error.message));
    if (active.has(job.state)) card.append(button('Cancel', () => api(`jobs/${job.id}/cancel`, 'POST', {})));
    if (['failed', 'cancelled', 'interrupted'].includes(job.state)) card.append(button('Retry', () => api(`jobs/${job.id}/retry`, 'POST', {})));
    $('jobs').append(card);
  }
}
function diffText(diff) {
  return [['Added', 'added'], ['Added to selection', 'addedToSelection'], ['Removed from selection', 'removedFromSelection'], ['Replaced', 'replaced']]
    .map(([label, key]) => `${label}: ${diff[key].join(', ') || 'none'}`).join(' · ');
}
function drawReview(data) {
  review = data;
  const sig = JSON.stringify(data); if (sig === reviewSignature) return; reviewSignature = sig;
  $('results').replaceChildren(); $('selection').replaceChildren();
  // Keep audio elements alive across unchanged polling responses.
  const latest = new Map(data.results.map(j => [j.item.id, j]));
  for (const [id, job] of latest) {
    const result = job.result, decision = data.decisions[id] || {}, card = el('div', undefined, 'card');
    const state = result.reject_reasons.length ? 'Rejected by processing' : decision.approved && decision.job === job.id ? 'Approved' : decision.approved === false ? 'Rejected by review' : 'Awaiting approval';
    card.append(el('h3', job.item.title), el('p', state, 'badge'));
    const player = el('audio'); player.controls = true; player.preload = 'none'; player.src = `/media/jobs/${job.id}/preview`; card.append(player);
    const loud = result.loudness;
    card.append(el('p', `${result.duration_sec.toFixed(1)} s · ${loud.i_lufs} LUFS · LRA ${loud.lra} LU · true peak ${loud.true_peak} dBTP`));
    card.append(el('p', 'Flags: ' + (result.flags.join(', ') || 'none')));
    if (result.reject_reasons.length) card.append(el('p', result.reject_reasons.join('; ')));
    const approve = button('Approve', () => api('review/' + id, 'POST', {approved: true, job: job.id}), false);
    approve.disabled = !!result.reject_reasons.length;
    card.append(approve, document.createTextNode(' '), button('Reject', () => api('review/' + id, 'POST', {approved: false})));
    $('results').append(card);
  }
  if (data.proposed) for (const track of data.proposed.tracks) {
    $('selection').append(check(track.title, data.proposed.selection.includes(track.id), value => api('review/' + track.id, 'POST', {inSelection: value})));
  }
  $('diff').textContent = data.error ? data.error.message : diffText(data.diff);
  $('publish').textContent = `Publish version ${data.current.version} → ${data.current.version + 1}`;
  $('publish').disabled = !!data.error || !data.proposed || !data.proposed.selection.length;
}
async function refresh() {
  clearTimeout(timer);
  try {
    const status = await api('jobs'), jobs = status.jobs;
    $('worker-error').hidden = status.workerAlive;
    drawJobs(jobs); drawReview(await api('review'));
    const history = await api('snapshots'); $('history').replaceChildren();
    if (!history.length) $('history').append(el('p', 'No published versions.', 'muted'));
    for (const snapshot of history) {
      const row = el('div', undefined, 'card');
      row.append(el('p', `Version ${snapshot.version} · ${snapshot.generated} · ${snapshot.counts.tracks} tracks · ${snapshot.counts.selection} selected` + (snapshot.rollbackOf ? ` · rollback of ${snapshot.rollbackOf}` : '')));
      row.append(button('Restore as a new version', async () => {
        const parent = review.current.version;
        if (confirm(`Restore version ${snapshot.version} as version ${parent + 1}? Current version: ${parent}.`)) {
          await api(`snapshots/${snapshot.version}/rollback`, 'POST', {parentVersion: parent, confirm: true}); await drafts(); await drawDraft();
        }
      })); $('history').append(row);
    }
    if (jobs.some(j => active.has(j.state))) timer = setTimeout(() => refresh().catch(e => error(e.message)), 1000);
  } catch (e) { error(e.message); timer = setTimeout(() => refresh().catch(err => error(err.message)), 1000); }
}
$('add').onsubmit = event => { event.preventDefault(); action(async () => {
  const draft = await api('drafts', 'POST', {url: $('url').value.trim()}); draftId = draft.id; await drafts(); await drawDraft();
}); };
$('draft-picker').onchange = () => action(async () => { draftId = $('draft-picker').value; await drawDraft(); });
$('publish').onclick = () => action(async () => {
  const parent = review.current.version;
  if (confirm(`Publish version ${parent} → ${parent + 1} to the local destination?\n${diffText(review.diff)}`)) {
    await api('publish', 'POST', {parentVersion: parent, confirm: true}); await drafts(); await drawDraft();
  }
});
action(async () => { await drafts(); });
