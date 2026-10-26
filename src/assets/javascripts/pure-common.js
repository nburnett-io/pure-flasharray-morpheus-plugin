/*
 * Pure FlashArray for HVM - helpers shared by the cluster tab and the instance Restore tab. Each tab script pulls this
 * in at build time (asset-pipeline "require"), so a tab never runs before its helpers exist.
 * window.Morpheus.pluginTabs.pureFlashArrayHvm.lib, defined once per page.
 */
(function () {
  'use strict';
  window.Morpheus = window.Morpheus || {};
  window.Morpheus.pluginTabs = window.Morpheus.pluginTabs || {};
  // shared by both tab scripts: add to the namespace, never replace it
  var ns = window.Morpheus.pluginTabs.pureFlashArrayHvm = window.Morpheus.pluginTabs.pureFlashArrayHvm || {};
  if (ns.lib) return;

  var BASE = '/plugin/pure-hvm/';

  function csrf() {
    var m = document.querySelector('meta[name="_csrf"]') || document.querySelector('meta[name="csrf-token"]');
    return m ? m.getAttribute('content') : null;
  }
  /** The CSRF header the Morpheus UI sends: named by the page's _csrf_header meta (X-XSRF-TOKEN on 9.1). */
  function csrfHeaders() {
    var h = {}, t = csrf(), hn = document.querySelector('meta[name="_csrf_header"]');
    if (t) h[(hn && hn.getAttribute('content')) || 'X-XSRF-TOKEN'] = t;
    return h;
  }
  function expired(r, what) {
    if (r.type === 'opaqueredirect') throw new Error(what + ': redirected (session expired); reload the page');
  }

  /**
   * call(path, params, post) for /plugin/pure-hvm/<path>, with {idKey: idValue} added to every request. The token
   * also goes in the POST body (_csrf), never in a GET URL (it would land in access logs).
   */
  function client(idKey, idValue, permissionMsg) {
    return function call(path, params, post) {
      params = params || {}; params[idKey] = idValue;
      var body = new URLSearchParams();
      Object.keys(params).forEach(function (k) { if (params[k] !== undefined && params[k] !== null) body.append(k, params[k]); });
      var t = csrf();
      if (post && t) body.append('_csrf', t);
      var opts = { method: post ? 'POST' : 'GET', credentials: 'same-origin', redirect: 'manual', headers: post ? csrfHeaders() : {} };
      if (post) opts.body = body;
      return fetch(BASE + path + (post ? '' : '?' + body.toString()), opts).then(function (r) {
        expired(r, path);
        return r.text().then(function (txt) {
          var j; try { j = JSON.parse(txt); } catch (e) {
            // Morpheus answers 404 (not 403) for plugin routes the user's role is not allowed to use
            if (r.status === 404 || r.status === 403) throw new Error(permissionMsg);
            throw new Error('HTTP ' + r.status + ' from ' + path);
          }
          if (!j.success) throw new Error(j.msg || ('HTTP ' + r.status));
          return j.data;
        });
      });
    };
  }

  /**
   * Morpheus's own /api as the logged-in user: session cookie plus the UI's CSRF header. Morpheus applies this user's
   * permissions; the plugin never needs a token of its own for these calls. Errors carry the HTTP status (e.status).
   */
  function mapi(method, path, payload) {
    var h = csrfHeaders();
    h['X-Requested-With'] = 'XMLHttpRequest'; h.Accept = 'application/json';
    var opts = { method: method, credentials: 'same-origin', headers: h, redirect: 'manual' };
    if (payload !== undefined) { h['Content-Type'] = 'application/json'; opts.body = JSON.stringify(payload); }
    return fetch(path, opts).then(function (r) {
      expired(r, method + ' ' + path);
      return r.text().then(function (txt) {
        var j; try { j = txt ? JSON.parse(txt) : {}; } catch (e) { throw new Error(method + ' ' + path + ': HTTP ' + r.status + ' (not JSON)'); }
        if (!r.ok || j.success === false) {
          var err = new Error(method + ' ' + path + ': ' + (j.msg || (j.errors && JSON.stringify(j.errors)) || ('HTTP ' + r.status)));
          err.status = r.status;
          throw err;
        }
        return j;
      });
    });
  }

  function pause(ms) { return new Promise(function (res) { setTimeout(res, ms); }); }

  /**
   * Polls a plugin job until it is no longer running; onUpdate(job) after every poll. A failed poll (network blip) is
   * retried three times before the promise rejects; the job itself keeps running on the server either way.
   */
  function followJob(call, route, job, params, onUpdate) {
    var misses = 0;
    if (onUpdate) onUpdate(job);
    return (function poll(j) {
      if (j.state !== 'running') return Promise.resolve(j);
      return pause(2500).then(function () {
        return call(route, Object.assign({ id: j.id }, params || {})).then(function (n) {
          misses = 0;
          if (onUpdate) onUpdate(n);
          return poll(n);
        }, function (e) {
          if (++misses > 3) throw e;
          return poll(j);
        });
      });
    })(job);
  }

  /**
   * Polls a Morpheus object (GET url) every 5 s until test(obj) returns a value other than undefined; obj is the
   * response's instance or server. Throws when Morpheus reports the object failed or after timeoutMs.
   */
  function waitFor(url, test, label, timeoutMs) {
    var t0 = Date.now();
    return (function poll() {
      return pause(5000).then(function () { return mapi('GET', url); }).then(function (j) {
        var o = j.instance || j.server;
        var v = test(o, Math.round((Date.now() - t0) / 1000));
        if (v !== undefined) return v;
        if (o.status === 'failed') throw new Error(label + ': Morpheus reports it failed (see its history)');
        if (Date.now() - t0 > timeoutMs) throw new Error(label + ': still ' + o.status + ' after ' + Math.round((Date.now() - t0) / 60000) + ' min');
        return poll();
      });
    })();
  }

  /**
   * One volume for a Reconfigure request. Every volume must go back in the request (one left out is removed);
   * /api/servers answers maxStorage / typeId where /api/instances answers size / storageType.
   */
  function reconfVolume(v, overrides) {
    return Object.assign({
      id: v.id, rootVolume: v.rootVolume, name: v.name,
      size: v.size != null ? v.size : Math.round((v.maxStorage || 0) / 1073741824),
      storageType: v.storageType != null ? v.storageType : (v.typeId != null ? v.typeId : (v.type && v.type.id)),
      datastoreId: v.datastoreId
    }, overrides || {});
  }

  function el(tag, attrs, kids) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) { if (k === 'text') e.textContent = attrs[k]; else e.setAttribute(k, attrs[k]); });
    (kids || []).forEach(function (c) { if (c != null) e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c); });
    return e;
  }
  function btn(label, fn, cls) { var b = el('button', { 'class': 'btn btn-sm ' + (cls || 'btn-default'), type: 'button', text: label }); b.addEventListener('click', fn); return b; }
  /** Button group with consistent spacing (flex gap); nulls are skipped. */
  function actions(buttons) { return el('div', { 'class': 'actions' }, buttons.filter(function (b) { return b; })); }
  function table(head, rows) {
    return el('table', {}, [el('thead', {}, [el('tr', {}, head.map(function (h) { return el('th', { text: h }); }))]),
      el('tbody', {}, rows.map(function (r) { return el('tr', {}, r.map(function (c) { return c instanceof Node ? el('td', {}, [c]) : el('td', { text: c == null ? '' : String(c) }); })); }))]);
  }
  /** Replaces a box's content (and drops its "Loading..." fading). */
  function put(box, node) { box.innerHTML = ''; box.classList.remove('muted'); if (node) box.appendChild(node); }
  function errBox(e) { return el('div', { 'class': 'err', text: e.message || String(e) }); }
  function gib(b) { return b ? (b / 1073741824).toFixed(1) + ' GiB' : ''; }

  /**
   * Starts a tab: ns[entry] for Morpheus's React loader, plus a scan of the page now and whenever it changes (one
   * observer for every registered tab).
   */
  var tabs = [];
  function scan() { tabs.forEach(function (t) { Array.prototype.forEach.call(document.querySelectorAll(t.selector), t.init); }); }
  function register(selector, entry, init) {
    ns[entry] = init;
    tabs.push({ selector: selector, init: init });
    scan();
    if (window.MutationObserver && !ns.observer) {
      ns.observer = new MutationObserver(scan);
      ns.observer.observe(document.documentElement, { childList: true, subtree: true });
    }
  }
  ns.unmount = function (root) { if (root) root.removeAttribute('data-pure-init'); };

  ns.lib = {
    client: client, mapi: mapi, pause: pause, followJob: followJob, waitFor: waitFor, reconfVolume: reconfVolume,
    el: el, btn: btn, actions: actions, table: table, put: put, errBox: errBox, gib: gib, register: register
  };
})();
