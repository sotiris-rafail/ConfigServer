(function () {
  'use strict';
  var app = document.getElementById('app');
  var modalRoot = document.getElementById('modal-root');
  var user = null;
  var data = { apps: [], profiles: [], entries: [], preview: null };
  var filter = { app: '', profile: '' };
  var previewQuery = { app: 'backend', profiles: '' };
  var busy = false;

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
      '<p class="err" id="login-err" role="alert"' + (err ? '' : ' hidden') + '>' + esc(err || '') + '</p>' +
      '<button class="btn" id="login-submit" type="submit">Log in</button></form>';
  }

  function table() {
    if (!data.entries.length) return '<div class="empty" id="empty">No settings match. Use "Add setting" to create one.</div>';
    return '<table id="entries"><thead><tr><th>Application</th><th>Profile</th><th>Key</th><th>Value</th><th></th></tr></thead><tbody>' +
      data.entries.map(function (e) {
        var val = e.secret ? '<span class="pill secret">secret</span> <span class="muted">hidden</span>' : (e.value === '' ? '<span class="muted">(empty)</span>' : esc(e.value));
        return '<tr data-id="' + e.id + '"><td>' + esc(e.app) + '</td><td>' + esc(e.profile) + '</td><td class="key">' + esc(e.key) +
          (e.description ? '<div class="muted" style="font-family:var(--font)">' + esc(e.description) + '</div>' : '') + '</td><td class="val">' + val + '</td>' +
          '<td class="act"><button class="btn ghost small" data-act="edit" data-id="' + e.id + '">Edit</button> ' +
          '<button class="btn danger small" data-act="delete" data-id="' + e.id + '">Delete</button></td></tr>';
      }).join('') + '</tbody></table>';
  }

  function opts(list, current, allLabel) {
    return (allLabel ? '<option value="">' + esc(allLabel) + '</option>' : '') + list.map(function (x) {
      return '<option value="' + esc(x) + '"' + (x === current ? ' selected' : '') + '>' + esc(x) + '</option>';
    }).join('');
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
      '<main>' +
      '<div class="bar"><div class="field"><label for="f-app">Application</label><select id="f-app">' + opts(data.apps, filter.app, 'All applications') + '</select></div>' +
      '<div class="field"><label for="f-profile">Profile</label><select id="f-profile">' + opts(data.profiles, filter.profile, 'All profiles') + '</select></div>' +
      '<div class="grow"></div><button class="btn" id="add" data-act="add">Add setting</button></div>' +
      '<div class="panel" id="list">' + table() + '</div>' +
      '<h2>Preview what an app receives</h2>' +
      '<p class="muted">Shared settings (application <code>application</code>) come first, then the app\'s own. The <code>default</code> profile comes before the profiles you list, and later ones win.</p>' +
      '<div class="bar"><div class="field"><label for="p-app">Application</label><input id="p-app" list="app-list" value="' + esc(previewQuery.app) + '"></div>' +
      '<div class="field"><label for="p-profiles">Profiles (comma separated)</label><input id="p-profiles" placeholder="e.g. prod" value="' + esc(previewQuery.profiles) + '"></div>' +
      '<button class="btn ghost" id="p-run" data-act="preview">Preview</button></div>' +
      '<div class="panel" id="preview">' + (data.preview ? previewTable() : '<div class="empty">Press Preview.</div>') + '</div>' +
      '<div class="help"><h3>How apps read these settings</h3>' +
      '<p>1. An application gets a token: <code>POST ' + esc(origin) + '/auth/token</code> with <code>{"clientId","clientSecret"}</code>.</p>' +
      '<p>2. It reads its settings: <code>GET ' + esc(origin) + '/config/backend?profiles=prod</code> with <code>Authorization: Bearer &lt;token&gt;</code> (the token decides which applications and whether secrets).</p>' +
      '</div>' +
      '</main><datalist id="app-list">' + data.apps.map(function (a) { return '<option value="' + esc(a) + '">'; }).join('') + '</datalist>';
  }

  function entryDialog(e) {
    var editing = !!e;
    e = e || { app: filter.app || 'backend', profile: filter.profile || '', key: '', value: '', secret: false, description: '' };
    var secretEdit = editing && e.secret;
    modalRoot.innerHTML =
      '<div class="scrim" data-act="close-bg"><form class="dialog" id="entry-form" role="dialog" aria-modal="true" aria-labelledby="dlg-title" novalidate>' +
      '<h2 id="dlg-title">' + (editing ? 'Edit setting' : 'Add setting') + '</h2>' +
      '<div class="field"><label for="e-app">Application</label><input id="e-app" list="app-list" maxlength="50" value="' + esc(e.app) + '"></div>' +
      '<div class="field"><label for="e-profile">Profile (empty = default)</label><input id="e-profile" list="profile-list" maxlength="50" value="' + esc(e.profile === 'default' ? '' : e.profile) + '"></div>' +
      '<div class="field"><label for="e-key">Key</label><input id="e-key" maxlength="200" value="' + esc(e.key) + '"></div>' +
      '<div class="field"><label for="e-value">Value</label><textarea id="e-value" maxlength="4000" placeholder="' + (secretEdit ? 'Leave empty to keep the current secret' : '') + '">' + esc(secretEdit ? '' : e.value) + '</textarea></div>' +
      '<div class="field check"><input type="checkbox" id="e-secret"' + (e.secret ? ' checked' : '') + '><label for="e-secret">Secret (never shown here, never sent to browsers)</label></div>' +
      '<div class="field"><label for="e-desc">Note</label><input id="e-desc" maxlength="300" value="' + esc(e.description || '') + '"></div>' +
      '<p class="err" id="e-err" role="alert" hidden></p>' +
      '<div class="dactions"><button type="button" class="btn ghost" id="e-cancel" data-act="close">Cancel</button><button type="submit" class="btn" id="e-save">Save</button></div>' +
      '<datalist id="profile-list">' + data.profiles.map(function (p) { return '<option value="' + esc(p) + '">'; }).join('') + '</datalist>' +
      '</form></div>';
    modalRoot.dataset.editing = editing ? String(e.id) : '';
    modalRoot.dataset.secret = secretEdit ? '1' : '';
    document.getElementById('e-key').focus();
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
        var err = document.getElementById('login-err');
        if (!u || !p) { err.textContent = 'Enter your username and password.'; err.hidden = false; return; }
        try { user = (await api('POST', '/api/auth/login', { username: u, password: p })).username; }
        catch (e) { err.textContent = e.message; err.hidden = false; return; }
        await reload(); consoleView();
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
        if (!body.app || !body.key) { err.textContent = 'Application and key are required.'; err.hidden = false; return; }
        try { await (id ? api('PUT', '/api/admin/entries/' + id, body) : api('POST', '/api/admin/entries', body)); }
        catch (e) { if (e.status === 401) throw e; err.textContent = e.message; err.hidden = false; return; }
        closeDialog(); await reload(); await refreshPreview(); consoleView(); toast('Saved.');
      });
    }
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
        guarded(async function () { await api('DELETE', '/api/admin/entries/' + id); await reload(); await refreshPreview(); consoleView(); toast('Deleted.'); });
      }
    } else if (act === 'close') closeDialog();
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
      await reload(); consoleView();
    } catch (e) {
      user = null;
      loginView(e.status && e.status !== 401 ? e.message : '');
    }
  })();
})();
