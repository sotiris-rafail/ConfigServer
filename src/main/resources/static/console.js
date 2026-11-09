(function () {
  'use strict';
  var app = document.getElementById('app');
  var modalRoot = document.getElementById('modal-root');
  var user = null;
  var data = { apps: [], profiles: [], entries: [], preview: null };
  var filter = { app: '', profile: '' };
  var previewQuery = { app: 'backend', profiles: '' };
  var busy = false;
  var tab = 'settings', search = '', needCode = false;
  var extra = { warnings: [], history: [], clients: [], twofa: null, setup: null, refreshed: null, transfer: null };
  function fmt(t) { return t ? new Date(t).toLocaleString() : 'never'; }

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }

  async function api(method, path, body) {
    var opt = { method: method, credentials: 'same-origin', headers: { Accept: 'application/json' } };
    if (body !== undefined) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
    var res = await fetch(path, opt);
    var txt = res.status === 204 ? '' : await res.text();
    var json = null;
    try { json = txt ? JSON.parse(txt) : null; } catch (e) { /* not JSON */ }
    if (!res.ok) {
      var err = new Error((json && json.message) || ('Request failed (' + res.status + ').'));
      err.status = res.status;
      throw err;
    }
    return json;
  }

  var tt;
  function toast(msg) {
    var el = document.getElementById('toast');
    el.textContent = msg; el.hidden = false;
    clearTimeout(tt); tt = setTimeout(function () { el.hidden = true; }, 3200);
  }

  /* ---------- views ---------- */
  function loginView(err) {
    app.innerHTML =
      '<form class="login" id="login-form" novalidate>' +
      '<h1>Config console</h1><p class="sub muted">Log in to edit the settings of the Foundry apps.</p>' +
      '<div class="field"><label for="login-user">Username</label><input id="login-user" autocomplete="username" autofocus></div>' +
      '<div class="field"><label for="login-pass">Password</label><input id="login-pass" type="password" autocomplete="current-password"></div>' +
      '<div class="field" id="code-field"' + (needCode ? '' : ' hidden') + '><label for="login-code">6-digit code from your authenticator app</label><input id="login-code" inputmode="numeric" autocomplete="one-time-code" maxlength="6"></div>' +
      '<p class="err" id="login-err" role="alert"' + (err ? '' : ' hidden') + '>' + esc(err || '') + '</p>' +
      '<button class="btn" id="login-submit" type="submit">Log in</button></form>';
  }

  function visible() {
    var q = search.trim().toLowerCase();
    return data.entries.filter(function (e) {
      return !q || [e.key, e.description, e.group, e.app, e.profile, e.secret ? '' : e.value].join(' ').toLowerCase().indexOf(q) >= 0;
    });
  }
  function table() {
    var list = visible();
    if (!list.length) return '<div class="empty" id="empty">No settings match. Use "Add setting" to create one.</div>';
    var last = null, rows = '';
    list.forEach(function (e) {
      var g = e.group || 'Other';
      if (g !== last) { rows += '<tr class="grp"><th colspan="5">' + esc(g) + '</th></tr>'; last = g; }
      var val = e.secret ? '<span class="pill secret">secret</span> <span class="muted">hidden</span>' : (e.value === '' ? '<span class="muted">(empty)</span>' : esc(e.value));
      rows += '<tr data-id="' + e.id + '"><td>' + esc(e.app) + '</td><td>' + esc(e.profile) + '</td><td class="key">' + esc(e.key) +
        (e.description ? '<div class="muted" style="font-family:var(--font)">' + esc(e.description) + '</div>' : '') +
        (e.hint ? '<div class="muted hint" style="font-family:var(--font)">' + esc(e.hint) + '</div>' : '') + '</td><td class="val">' + val + '</td>' +
        '<td class="act"><button class="btn ghost small" data-act="edit" data-id="' + e.id + '">Edit</button> ' +
        '<button class="btn danger small" data-act="delete" data-id="' + e.id + '">Delete</button></td></tr>';
    });
    return '<table id="entries"><thead><tr><th>Application</th><th>Profile</th><th>Key</th><th>Value</th><th></th></tr></thead><tbody>' + rows + '</tbody></table>';
  }
  function warningsBanner() {
    if (!extra.warnings.length) return '';
    return '<div class="warn" id="warnings" role="status"><b>Check these settings</b><ul>' + extra.warnings.map(function (w) { return '<li>' + esc(w) + '</li>'; }).join('') + '</ul></div>';
  }
  function historyView() {
    if (!extra.history.length) return '<div class="empty" id="history-empty">No changes recorded yet.</div>';
    return '<table id="history"><thead><tr><th>When</th><th>Who</th><th>Setting</th><th>Change</th><th></th></tr></thead><tbody>' + extra.history.map(function (h) {
      var change = h.secret ? '<span class="muted">secret value (not recorded)</span>' : (esc(h.oldValue == null ? '(none)' : h.oldValue) + ' &rarr; ' + esc(h.newValue == null ? '(removed)' : h.newValue));
      return '<tr><td>' + esc(fmt(h.at)) + '</td><td>' + esc(h.by) + '</td><td class="key">' + esc(h.app + '/' + h.profile + ' ' + h.key) + '<div class="muted">' + esc(h.action) + '</div></td><td>' + change + '</td>' +
        '<td class="act">' + (h.undoable ? '<button class="btn ghost small" data-act="undo" data-id="' + h.id + '">Undo</button>' : '') + '</td></tr>';
    }).join('') + '</tbody></table>';
  }
  function clientsView() {
    var rows = extra.clients.map(function (c) {
      return '<tr><td><b>' + esc(c.id) + '</b><div class="muted">apps: ' + esc((c.apps || []).join(', ')) + (c.secrets ? ' &middot; gets secrets' : '') + '</div></td>' +
        '<td>' + (c.disabled ? '<span class="pill off">switched off</span>' : '<span class="pill on">active</span>') + '</td>' +
        '<td>Last read: ' + esc(fmt(c.lastFetchAt)) + '<div class="muted">Token issued: ' + esc(fmt(c.lastTokenAt)) + '</div></td>' +
        '<td>' + (c.changesNotFetched ? '<span class="pill warn">' + c.changesNotFetched + ' change(s) not read yet</span>' : '<span class="muted">up to date</span>') + '</td>' +
        '<td class="act"><button class="btn ghost small" data-act="toggle-client" data-id="' + esc(c.id) + '" data-disabled="' + (c.disabled ? '1' : '') + '">' + (c.disabled ? 'Switch on' : 'Switch off') + '</button></td></tr>';
    }).join('');
    var res = extra.refreshed ? '<p id="refresh-result" class="muted">' + extra.refreshed.map(function (r) { return esc(r.client + ': ' + r.message); }).join(' | ') + '</p>' : '';
    return '<div class="bar"><button class="btn" id="refresh-now" data-act="refresh-now">Refresh now</button><span class="muted">Asks each app that has a refresh address to read its settings right away instead of waiting for its next check.</span></div>' + res +
      '<div class="panel"><table id="clients"><thead><tr><th>Client</th><th>State</th><th>Activity</th><th>Pending</th><th></th></tr></thead><tbody>' + rows + '</tbody></table></div>';
  }
  function toolsView() {
    var t = extra.transfer ? '<pre id="transfer-result" class="result">' + esc(JSON.stringify(extra.transfer, null, 2)) + '</pre>' : '';
    return '<h2>Export</h2><p class="muted">Downloads settings as a file. Secrets stay out unless you tick the box (the file then holds them in plain text - keep it safe).</p>' +
      '<div class="bar"><div class="field"><label for="x-app">Application (empty = all)</label>' + appSelect('x-app', '', 'All applications') + '</div>' +
      '<div class="field"><label for="x-profile">Profile (empty = all)</label><input id="x-profile" list="profile-list"></div>' +
      '<div class="field check"><input type="checkbox" id="x-secrets"><label for="x-secrets">Include secrets</label></div>' +
      '<button class="btn" id="x-run" data-act="export">Export</button></div>' +
      '<h2>Import</h2><p class="muted">Choose an exported file. "Check only" shows what would happen without changing anything.</p>' +
      '<div class="bar"><div class="field"><label for="i-file">File</label><input id="i-file" type="file" accept=".json,application/json"></div>' +
      '<div class="field check"><input type="checkbox" id="i-over"><label for="i-over">Replace settings that already exist</label></div>' +
      '<button class="btn ghost" id="i-check" data-act="import-check">Check only</button><button class="btn" id="i-run" data-act="import-run">Import</button></div>' +
      '<h2>Copy a profile</h2><p class="muted">Copies every setting of one profile into another, for example prod into staging. Use "default" for the default profile.</p>' +
      '<div class="bar"><div class="field"><label for="c-app">Application</label>' + appSelect('c-app', previewQuery.app, 'Choose an application') + '</div>' +
      '<div class="field"><label for="c-from">From profile</label><input id="c-from" list="profile-list" value="default"></div>' +
      '<div class="field"><label for="c-to">To profile</label><input id="c-to" list="profile-list"></div>' +
      '<div class="field check"><input type="checkbox" id="c-over"><label for="c-over">Replace existing</label></div>' +
      '<button class="btn" id="c-run" data-act="clone">Copy</button></div>' + t +
      '<datalist id="profile-list">' + data.profiles.map(function (p) { return '<option value="' + esc(p) + '">'; }).join('') + '</datalist>';
  }
  function securityView() {
    var on = extra.twofa;
    var body;
    if (on) body = '<p>Two-step sign-in is <b>on</b>. To switch it off, enter a current code.</p><div class="bar"><div class="field"><label for="t-code">Code</label><input id="t-code" inputmode="numeric" maxlength="6"></div><button class="btn danger" id="t-off" data-act="2fa-off">Switch off</button></div>';
    else if (extra.setup) body = '<p>Add this key to your authenticator app (choose "enter a setup key", time based), then type the 6-digit code it shows.</p><p>Key: <code id="t-secret">' + esc(extra.setup.secret) + '</code></p><p class="muted">Or open this address on your phone: <code>' + esc(extra.setup.uri) + '</code></p>' +
      '<div class="bar"><div class="field"><label for="t-code">Code</label><input id="t-code" inputmode="numeric" maxlength="6"></div><button class="btn" id="t-on" data-act="2fa-on">Turn on</button></div>';
    else body = '<p>Two-step sign-in is <b>off</b>. Turning it on asks for a code from an authenticator app each time you log in.</p><button class="btn" id="t-setup" data-act="2fa-setup">Set up</button>';
    return '<h2>Two-step sign-in</h2><div class="panel pad">' + body + '</div>';
  }
  function tabBody() {
    if (tab === 'history') return '<div class="panel">' + historyView() + '</div>';
    if (tab === 'clients') return clientsView();
    if (tab === 'tools') return toolsView();
    if (tab === 'security') return securityView();
    return null;
  }

  function opts(list, current, allLabel) {
    return (allLabel ? '<option value="">' + esc(allLabel) + '</option>' : '') + list.map(function (x) {
      return '<option value="' + esc(x) + '"' + (x === current ? ' selected' : '') + '>' + esc(x) + '</option>';
    }).join('');
  }

  /* A drop-down with every application. `current` that is not in the list is kept; `empty` is the label of the empty choice (or '' for none). */
  function appSelect(id, current, empty) {
    var list = data.apps.slice();
    if (current && list.indexOf(current) < 0) list.push(current);
    return '<select id="' + id + '">' + opts(list, current, empty) + '</select>';
  }

  function previewTable() {
    var p = data.preview;
    if (!p) return '';
    if (!p.length) return '<div class="empty">No settings for this application and profiles.</div>';
    return '<table id="preview-table"><thead><tr><th>Key</th><th>Value the app receives</th><th>From</th></tr></thead><tbody>' +
      p.map(function (r) {
        return '<tr><td class="key">' + esc(r.key) + '</td><td class="val">' + (r.secret ? '<span class="pill secret">secret</span> <span class="muted">hidden</span>' : esc(r.value)) + '</td><td class="muted">' + esc(r.source) + '</td></tr>';
      }).join('') + '</tbody></table>';
  }

  function consoleView() {
    var origin = location.origin;
    app.innerHTML =
      '<header class="top"><div><h1>Foundry config console</h1><small>One place for the backend and UI settings</small></div>' +
      '<div class="who"><span>Signed in as <b>' + esc(user) + '</b></span><button class="btn ghost" id="logout" data-act="logout">Log out</button></div></header>' +
      '<main>' + warningsBanner() +
      '<nav class="tabs" role="tablist">' + [['settings', 'Settings'], ['history', 'History'], ['clients', 'Clients'], ['tools', 'Tools'], ['security', 'Security']].map(function (t) {
        return '<button class="tab' + (tab === t[0] ? ' on' : '') + '" role="tab" aria-selected="' + (tab === t[0]) + '" data-act="tab" data-tab="' + t[0] + '" id="tab-' + t[0] + '">' + t[1] + '</button>';
      }).join('') + '</nav>' + (tabBody() !== null ? '<div id="tab-body">' + tabBody() + '</div>' : '') + '<div id="settings-pane"' + (tab === 'settings' ? '' : ' hidden') + '>' +
      '<div class="bar"><div class="field"><label for="f-app">Application</label><select id="f-app">' + opts(data.apps, filter.app, 'All applications') + '</select></div>' +
      '<div class="field"><label for="f-profile">Profile</label><select id="f-profile">' + opts(data.profiles, filter.profile, 'All profiles') + '</select></div>' +
      '<div class="field"><label for="f-search">Search</label><input id="f-search" type="search" placeholder="key, note or value" value="' + esc(search) + '"></div>' +
      '<div class="grow"></div><button class="btn" id="add" data-act="add">Add setting</button></div>' +
      '<div class="panel" id="list">' + table() + '</div>' +
      '<h2>Preview what an app receives</h2>' +
      '<p class="muted">Shared settings (application <code>application</code>) come first, then the app\'s own. The <code>default</code> profile comes before the profiles you list, and later ones win.</p>' +
      '<div class="bar"><div class="field"><label for="p-app">Application</label>' + appSelect('p-app', previewQuery.app, '') + '</div>' +
      '<div class="field"><label for="p-profiles">Profiles (comma separated)</label><input id="p-profiles" placeholder="e.g. prod" value="' + esc(previewQuery.profiles) + '"></div>' +
      '<button class="btn ghost" id="p-run" data-act="preview">Preview</button></div>' +
      '<div class="panel" id="preview">' + (data.preview ? previewTable() : '<div class="empty">Press Preview.</div>') + '</div>' +
      '<div class="help"><h3>How apps read these settings</h3>' +
      '<p>1. An application gets a token: <code>POST ' + esc(origin) + '/auth/token</code> with <code>{"clientId","clientSecret"}</code>.</p>' +
      '<p>2. It reads its settings: <code>GET ' + esc(origin) + '/config/backend?profiles=prod</code> with <code>Authorization: Bearer &lt;token&gt;</code> (the token decides which applications and whether secrets).</p>' +
      '</div></div>' +
      '</main><datalist id="app-list">' + data.apps.map(function (a) { return '<option value="' + esc(a) + '">'; }).join('') + '</datalist>';
  }

  function entryDialog(e) {
    var editing = !!e;
    e = e || { app: '', profile: filter.profile || '', key: '', value: '', secret: false, description: '' };
    var secretEdit = editing && e.secret;
    modalRoot.innerHTML =
      '<div class="scrim" data-act="close-bg"><form class="dialog" id="entry-form" role="dialog" aria-modal="true" aria-labelledby="dlg-title" novalidate>' +
      '<h2 id="dlg-title">' + (editing ? 'Edit setting' : 'Add setting') + '</h2>' +
      '<div class="field"><label for="e-app">Application</label>' + appSelect('e-app', e.app, 'Choose an application') + '<p class="err" id="e-app-err" role="alert" hidden>Choose an application.</p></div>' +
      '<div class="field"><label for="e-profile">Profile (empty = default)</label><input id="e-profile" list="profile-list" maxlength="50" value="' + esc(e.profile === 'default' ? '' : e.profile) + '"></div>' +
      '<div class="field"><label for="e-key">Key</label><input id="e-key" maxlength="200" value="' + esc(e.key) + '"></div>' +
      '<div class="field"><label for="e-value">Value</label><textarea id="e-value" maxlength="4000" placeholder="' + (secretEdit ? 'Leave empty to keep the current secret' : '') + '">' + esc(secretEdit ? '' : e.value) + '</textarea></div>' +
      (e.hint ? '<p class="muted" id="e-hint">' + esc(e.hint) + '</p>' : '') +
      '<div class="field check"><input type="checkbox" id="e-secret"' + (e.secret ? ' checked' : '') + '><label for="e-secret">Secret (never shown here, never sent to browsers)</label></div>' +
      '<div class="field"><label for="e-desc">Note</label><input id="e-desc" maxlength="300" value="' + esc(e.description || '') + '"></div>' +
      '<p class="err" id="e-err" role="alert" hidden></p>' +
      '<div class="dactions"><button type="button" class="btn ghost" id="e-cancel" data-act="close">Cancel</button><button type="submit" class="btn" id="e-save">Save</button></div>' +
      '<datalist id="profile-list">' + data.profiles.map(function (p) { return '<option value="' + esc(p) + '">'; }).join('') + '</datalist>' +
      '</form></div>';
    modalRoot.dataset.editing = editing ? String(e.id) : '';
    modalRoot.dataset.secret = secretEdit ? '1' : '';
    document.getElementById(editing ? 'e-key' : 'e-app').focus();
  }
  function closeDialog() { modalRoot.innerHTML = ''; modalRoot.dataset.editing = ''; }

  /* ---------- data ---------- */
  async function reload() {
    var q = [];
    if (filter.app) q.push('app=' + encodeURIComponent(filter.app));
    if (filter.profile) q.push('profile=' + encodeURIComponent(filter.profile));
    var r = await Promise.all([api('GET', '/api/admin/apps'), api('GET', '/api/admin/entries' + (q.length ? '?' + q.join('&') : ''))]);
    data.apps = r[0].apps; data.profiles = r[0].profiles; data.entries = r[1];
  }
  async function loadTab() {
    var base = 'app=' + encodeURIComponent(previewQuery.app);
    try { extra.warnings = await api('GET', '/api/admin/warnings?' + base); } catch (e) { if (e.status === 401) throw e; extra.warnings = []; }
    if (tab === 'history') extra.history = await api('GET', '/api/admin/history?limit=100');
    else if (tab === 'clients') extra.clients = await api('GET', '/api/admin/clients');
    else if (tab === 'security') extra.twofa = (await api('GET', '/api/admin/2fa')).enabled;
  }
  async function show() { await reload(); await loadTab(); consoleView(); }
  function download(name, obj) {
    var a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([JSON.stringify(obj, null, 2)], { type: 'application/json' }));
    a.download = name; document.body.appendChild(a); a.click(); a.remove();
  }
  async function readImport() {
    var f = document.getElementById('i-file').files[0];
    if (!f) throw new Error('Choose a file first.');
    var j; try { j = JSON.parse(await f.text()); } catch (e) { throw new Error('That file is not a valid export.'); }
    return { entries: j.entries || [], overwrite: document.getElementById('i-over').checked };
  }
  async function refreshPreview() {
    if (!data.preview) return;
    try { data.preview = await api('GET', '/api/admin/preview?app=' + encodeURIComponent(previewQuery.app) + '&profiles=' + encodeURIComponent(previewQuery.profiles)); }
    catch (e) { data.preview = null; }
  }
  function sessionEnded() { user = null; closeDialog(); loginView('Your session ended. Please log in again.'); }
  async function guarded(fn) {
    if (busy) return;
    busy = true;
    try { await fn(); }
    catch (e) { if (e.status === 401 && user) sessionEnded(); else toast(e.message); }
    finally { busy = false; }
  }

  /* ---------- events ---------- */
  document.addEventListener('submit', function (ev) {
    if (ev.target.id === 'login-form') {
      ev.preventDefault();
      guarded(async function () {
        var u = document.getElementById('login-user').value.trim(), p = document.getElementById('login-pass').value;
        var code = document.getElementById('login-code').value.trim();
        var err = document.getElementById('login-err');
        if (!u || !p) { err.textContent = 'Enter your username and password.'; err.hidden = false; return; }
        try { user = (await api('POST', '/api/auth/login', { username: u, password: p, code: code })).username; }
        catch (e) {
          if (e.status === 428) { needCode = true; document.getElementById('code-field').hidden = false; document.getElementById('login-code').focus(); }
          err.textContent = e.message; err.hidden = false; return;
        }
        needCode = false; await show();
      });
    } else if (ev.target.id === 'entry-form') {
      ev.preventDefault();
      guarded(async function () {
        var id = modalRoot.dataset.editing, secretEdit = modalRoot.dataset.secret === '1';
        var secret = document.getElementById('e-secret').checked, value = document.getElementById('e-value').value;
        var body = {
          app: document.getElementById('e-app').value.trim(), profile: document.getElementById('e-profile').value.trim(),
          key: document.getElementById('e-key').value.trim(), value: (secretEdit && value === '') ? null : value,
          secret: secret, description: document.getElementById('e-desc').value.trim()
        };
        var err = document.getElementById('e-err');
        var appBad = !body.app;
        document.getElementById('e-app-err').hidden = !appBad;
        document.getElementById('e-app').setAttribute('aria-invalid', appBad ? 'true' : 'false');
        if (appBad) { err.hidden = true; document.getElementById('e-app').focus(); return; }
        if (!body.key) { err.textContent = 'Enter the key (the name of the setting).'; err.hidden = false; document.getElementById('e-key').focus(); return; }
        try { await (id ? api('PUT', '/api/admin/entries/' + id, body) : api('POST', '/api/admin/entries', body)); }
        catch (e) { if (e.status === 401) throw e; err.textContent = e.message; err.hidden = false; return; }
        closeDialog(); await refreshPreview(); await show(); toast('Saved.');
      });
    }
  });

  document.addEventListener('input', function (ev) {
    if (ev.target.id === 'f-search') { search = ev.target.value; document.getElementById('list').innerHTML = table(); }
  });

  document.addEventListener('change', function (ev) {
    if (ev.target.id === 'f-app' || ev.target.id === 'f-profile') {
      filter.app = document.getElementById('f-app').value; filter.profile = document.getElementById('f-profile').value;
      guarded(async function () { await reload(); document.getElementById('list').innerHTML = table(); });
    }
  });

  document.addEventListener('click', function (ev) {
    var t = ev.target.closest('[data-act]');
    if (!t || t.disabled) return;
    var act = t.dataset.act, id = t.dataset.id ? Number(t.dataset.id) : null;
    if (act === 'logout') {
      guarded(async function () { try { await api('POST', '/api/auth/logout'); } catch (e) { /* gone anyway */ } user = null; loginView(); });
    } else if (act === 'add') entryDialog(null);
    else if (act === 'edit') entryDialog(data.entries.filter(function (x) { return x.id === id; })[0]);
    else if (act === 'delete') {
      var e = data.entries.filter(function (x) { return x.id === id; })[0];
      if (e && window.confirm('Delete ' + e.app + '/' + e.profile + ' ' + e.key + '?')) {
        guarded(async function () { await api('DELETE', '/api/admin/entries/' + id); await refreshPreview(); await show(); toast('Deleted.'); });
      }
    } else if (act === 'tab') { tab = t.dataset.tab; extra.setup = null; guarded(show); }
    else if (act === 'undo') guarded(async function () { await api('POST', '/api/admin/history/' + id + '/undo'); await show(); toast('Change undone.'); });
    else if (act === 'toggle-client') guarded(async function () { await api('PUT', '/api/admin/clients/' + encodeURIComponent(t.dataset.id) + '/disabled?value=' + (t.dataset.disabled ? 'false' : 'true')); await show(); });
    else if (act === 'refresh-now') guarded(async function () { extra.refreshed = await api('POST', '/api/admin/refresh'); await show(); });
    else if (act === 'export') guarded(async function () {
      var q = ['secrets=' + document.getElementById('x-secrets').checked];
      var a = document.getElementById('x-app').value.trim(), p = document.getElementById('x-profile').value.trim();
      if (a) q.push('app=' + encodeURIComponent(a)); if (p) q.push('profile=' + encodeURIComponent(p));
      download('foundry-config.json', await api('GET', '/api/admin/export?' + q.join('&')));
    });
    else if (act === 'import-check' || act === 'import-run') guarded(async function () {
      var body = await readImport(); body.dryRun = act === 'import-check';
      extra.transfer = await api('POST', '/api/admin/import', body);
      if (!body.dryRun) { await reload(); await loadTab(); }
      consoleView(); toast(body.dryRun ? 'Checked, nothing changed.' : 'Imported.');
    });
    else if (act === 'clone') guarded(async function () {
      extra.transfer = await api('POST', '/api/admin/clone', { app: document.getElementById('c-app').value.trim(), from: document.getElementById('c-from').value.trim(), to: document.getElementById('c-to').value.trim(), overwrite: document.getElementById('c-over').checked });
      await reload(); await loadTab(); consoleView(); toast('Copied.');
    });
    else if (act === '2fa-setup') guarded(async function () { extra.setup = await api('POST', '/api/admin/2fa/setup'); consoleView(); });
    else if (act === '2fa-on') guarded(async function () { await api('POST', '/api/admin/2fa/enable', { code: document.getElementById('t-code').value.trim() }); extra.setup = null; await show(); toast('Two-step sign-in is on.'); });
    else if (act === '2fa-off') guarded(async function () { await api('POST', '/api/admin/2fa/disable', { code: document.getElementById('t-code').value.trim() }); await show(); toast('Two-step sign-in is off.'); });
    else if (act === 'close') closeDialog();
    else if (act === 'close-bg') { if (ev.target === t) closeDialog(); }
    else if (act === 'preview') {
      previewQuery.app = document.getElementById('p-app').value.trim(); previewQuery.profiles = document.getElementById('p-profiles').value.trim();
      guarded(async function () {
        try { data.preview = await api('GET', '/api/admin/preview?app=' + encodeURIComponent(previewQuery.app) + '&profiles=' + encodeURIComponent(previewQuery.profiles)); }
        catch (er) { if (er.status === 401) throw er; data.preview = null; toast(er.message); return; }
        document.getElementById('preview').innerHTML = previewTable();
      });
    }
  });

  document.addEventListener('keydown', function (ev) { if (ev.key === 'Escape' && modalRoot.innerHTML) closeDialog(); });

  /* ---------- start ---------- */
  (async function boot() {
    try {
      user = (await api('GET', '/api/auth/me')).username;
      await show();
    } catch (e) {
      user = null;
      loginView(e.status && e.status !== 401 ? e.message : '');
    }
  })();
})();
