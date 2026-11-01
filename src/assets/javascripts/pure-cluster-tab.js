//= require pure-common
/*
 * Pure FlashArray for HVM - cluster tab UI. Served as a plugin asset (not inline) because Morpheus inserts tab HTML
 * in ways that never execute inline scripts. Starts in either of two ways:
 *  - React loaders: window.Morpheus.pluginTabs.pureFlashArrayHvm.clusterTab(root), via data-plugin-react-root markers
 *  - otherwise: initialises every .pure-hvm root present now or added later (pure-common's register)
 * All data comes from the plugin controller under /plugin/pure-hvm/*.
 */
(function () {
  'use strict';
  var lib = window.Morpheus.pluginTabs.pureFlashArrayHvm.lib;
  var el = lib.el, btn = lib.btn, actions = lib.actions, table = lib.table, gib = lib.gib, mapi = lib.mapi;

  function init(root) {
    if (!root || root.getAttribute('data-pure-init')) return;
    root.setAttribute('data-pure-init', '1');
    var clusterId = root.getAttribute('data-cluster-id');
    var state = { status: null, watching: {} };
    var call = lib.client('clusterId', clusterId, 'Your role does not have the "Pure FlashArray Operations" permission (Administration > Roles > your role > ' +
      'Pure FlashArray Operations: Read to view, Full to make changes). Ask an administrator to grant it.');

    /** A button that changes the storage itself: hidden without "Pure FlashArray Administration" (the route refuses it too). */
    function adm(b) { return state.status && state.status.canAdmin === false ? null : b; }
    function section(id) { return root.querySelector('[data-section="' + id + '"]'); }
    function body(id) { return section(id).querySelector('.ph-body'); }
    function show(id, node) { lib.put(body(id), node); }
    function err(id, e) { show(id, lib.errBox(e)); }

    function renderJob(j) {
      var box = root.querySelector('[data-job="' + j.id + '"]');
      if (!box) { box = el('div', { 'data-job': j.id }); body('jobs').prepend(box); }
      box.innerHTML = '';
      box.appendChild(el('div', {}, [el('strong', { text: j.kind }), ' ', el('span', { 'class': j.state === 'failed' ? 'err' : 'muted', text: j.state + (j.error ? ': ' + j.error : '') })]));
      box.appendChild(el('pre', { text: (j.progress || []).join('\n') }));
    }
    /** Follows a job in the Operations list (once per job, also for jobs found running when the tab opens). */
    function watch(job, then) {
      if (state.watching[job.id]) { renderJob(job); return; }
      state.watching[job.id] = 1;
      lib.followJob(call, 'job', job, {}, renderJob).then(function (j) {
        delete state.watching[job.id];
        if (then) then(j);
      }, function (e) {
        delete state.watching[job.id];
        body('jobs').prepend(lib.errBox(new Error('Lost track of job ' + job.kind + ': ' + e.message + ' (it may still be running; reload the tab)')));
        if (then) then({ state: 'failed', error: e.message, progress: job.progress });
      });
    }
    function start(path, params, then) {
      return call(path, params, true).then(function (j) { watch(j, then); })
        .catch(function (e) { body('jobs').prepend(lib.errBox(e)); });
    }

    /** Storage-server name of a linked array, for labels. */
    function arrayName(sid) {
      var l = ((state.status && state.status.links) || []).filter(function (x) { return String(x.storageServerId) === String(sid); })[0];
      return l ? (l.server || (l.array && l.array.name) || ('array ' + sid)) : (sid ? 'array ' + sid : '');
    }

    function linkPicker(s, label) {
      var linked = (s.links || []).map(function (l) { return String(l.storageServerId); });
      var sel = el('select', {});
      var go = btn(label, function () {
        if (!sel.value) return;
        call('link', { storageServerId: sel.value }, true).then(function () { state.hostsLoaded = false; loadStatus(); }).catch(function (e) { alert(e.message); });
      }, 'btn-primary');
      call('arrays').then(function (list) {
        list = list.filter(function (x) { return linked.indexOf(String(x.id)) < 0; });
        if (!list.length) { sel.appendChild(el('option', { value: '', text: linked.length ? 'every Pure array is already linked' : 'no Pure FlashArray (HVM) storage servers' })); go.disabled = true; }
        list.forEach(function (x) { sel.appendChild(el('option', { value: x.id, text: x.name + ' (' + (x.version || x.status || '') + ')' })); });
      }).catch(function (e) { err('array', e); });
      return [sel, go];
    }

    function loadStatus() {
      return call('status').then(function (s) {
        state.status = s;
        root.classList.toggle('ph-no-admin', s.canAdmin === false);
        if (!s.linked) {
          show('array', s.canAdmin === false ? el('div', { 'class': 'muted', text: 'Not linked to a FlashArray yet. Linking needs the "Pure FlashArray Administration" permission.' })
            : el('div', {}, [el('div', { 'class': 'row' }, ['Not linked. Array: '].concat(linkPicker(s, 'Link cluster'))),
              el('div', { 'class': 'muted', text: 'Add the array under Infrastructure > Storage > Servers (type "Pure FlashArray (HVM)") first.' })]));
          return;
        }
        renderAutoCompliance(s);
        show('array', el('div', {}, [table(['Array', 'Purity / REST', 'Host group', 'iSCSI portals', ''], s.links.map(function (l) {
          var unlink = adm(btn('Unlink', function () {
            if (confirm('Unlink ' + arrayName(l.storageServerId) + ' from this cluster? Nothing on the array or the hosts changes; the plugin just stops managing it here.'))
              call('unlink', { storageServerId: l.storageServerId }, true).then(function () { state.hostsLoaded = false; loadStatus(); }).catch(function (e) { alert(e.message); });
          }));
          if (l.error) return [arrayName(l.storageServerId), el('span', { 'class': 'err', text: l.error }), l.hostGroup || 'not set', '', actions([unlink])];
          return [l.server + (l.array.name !== l.server ? ' (' + l.array.name + ')' : ''), l.array.version + ' / ' + l.array.api, l.hostGroup || 'not set',
            (l.portals || []).map(function (p) { return p.address + (p.mtu ? ' (mtu ' + p.mtu + ')' : ''); }).join(', '), actions([unlink])];
        })),
          s.canAdmin === false ? null : el('div', { 'class': 'row' }, ['Link another array: '].concat(linkPicker(s, 'Link'))),
          el('div', { 'class': 'muted', text: 'Hosts: ' + (s.hosts || []).join(', ') })]));
        var pick = root.querySelector('[data-field="ds-array"]');
        var keep = pick.value; pick.innerHTML = '';
        s.links.forEach(function (l) {
          pick.appendChild(el('option', { value: l.storageServerId, text: arrayName(l.storageServerId) + (l.hostGroup ? '' : ' (host group not set)') }));
        });
        if (keep) pick.value = keep;
        pick.classList.toggle('ph-hidden', s.links.length < 2);
        renderDatastores(s);
        renderRecoveries(s);
        (s.jobs || []).forEach(function (j) { if (j.state === 'running') watch(j); else renderJob(j); });
        if (!state.hostsLoaded) { state.hostsLoaded = true; loadHosts(); }
        if (!state.policiesLoaded) { state.policiesLoaded = true; loadPolicies(); }
      }).catch(function (e) { err('array', e); });
    }

    function renderDatastores(s) {
      var many = s.links.length > 1;
      var vols = [];
      s.links.forEach(function (l) { (l.volumes || []).forEach(function (v) { vols.push(v); }); });
      var rows = vols.map(function (v) {
        var grow = adm(btn('Grow', function () {
          var g = prompt('New size in GiB for ' + v.name + ' (currently ' + gib(v.provisioned) + ')');
          if (g) start('datastore-grow', { volume: v.name, sizeGiB: g, storageServerId: v.storageServerId }, loadStatus);
        }));
        var snaps = btn('Snapshots', function () { loadSnapshots(v); });
        var refresh = btn('Refresh capacity', function () { start('datastore-refresh', { volume: v.name, storageServerId: v.storageServerId }, loadStatus); });
        var dsName = (v.tags || {})['datastore-name'] || '';
        var acts;
        if (v.morpheusMissing) {
          // Morpheus deleted the datastore but the LUN is still here: a Retire that stopped before the array cleanup
          acts = [el('span', { 'class': 'st-WARN', text: 'Morpheus datastore deleted; LUN still on the array' }), adm(btn('Finish retire', function () {
            if (confirm('Finish retiring ' + v.name + '?\n\nRemoves the LUN from every host, disconnects it and destroys it on ' + arrayName(v.storageServerId) +
                ' (it stays in the destroyed bucket until the array eradicates it). The datastore record is already gone from Morpheus, ' +
                'so restore leftovers (.pre-restore-*) go with it; any other file stops the cleanup.'))
              start('retire-finish', { datastoreId: v.datastoreId, volume: v.name, storageServerId: v.storageServerId, allowLeftovers: true }, loadStatus);
          }, 'btn-danger'))];
        } else {
          var retireBtn = v.datastoreId ? adm(btn('Retire...', function () { retire(v); }, 'btn-danger')) : null;
          var tenantsBtn = v.datastoreId ? adm(btn('Tenants...', function () { tenants(v); })) : null;
          var reactivate = v.morpheusActive === false ? adm(btn('Re-activate', function () {
            call('retire-morpheus', { op: 'undrain', datastoreId: v.datastoreId }, true)
              .then(loadStatus).catch(function (e) { alert(e.message); });
          }, 'btn-warning')) : null;
          acts = [grow, snaps, refresh, tenantsBtn, reactivate, retireBtn];
          if (v.morpheusActive === false) dsName += ' (drained)';
        }
        var r = [v.name, dsName, gib(v.provisioned), gib(v.used), v.devicePath, actions(acts)];
        if (many) r.unshift(arrayName(v.storageServerId));
        return r;
      });
      var head = ['Volume', 'Datastore', 'Size', 'Used on array', 'Device', ''];
      if (many) head.unshift('Array');
      var wrap = el('div', {}, [rows.length ? table(head, rows)
                                            : el('div', { 'class': 'muted', text: 'No datastores managed by this plugin on this cluster yet.' })]);
      var existing = s.existing || [];
      if (existing.length) {
        var adoptable = existing.filter(function (x) { return x.state === 'adopt'; });
        var head2 = el('div', { 'class': 'row' }, [el('strong', { text: 'Found on ' + (many ? 'the linked arrays' : 'this array') + ' (created outside the plugin)' })]);
        if (adoptable.length > 1 && adm(true)) head2.appendChild(btn('Adopt all (' + adoptable.length + ')', function () {
          if (confirm('Adopt ' + adoptable.length + ' existing datastores? This only tags their array volumes; nothing else changes.'))
            call('adopt', { all: true }, true).then(loadStatus).catch(function (e) { alert(e.message); });
        }, 'btn-primary'));
        wrap.appendChild(head2);
        wrap.appendChild(table(['Datastore', 'Array', 'Array volume', 'Size', 'Device', 'Status', ''], existing.map(function (x) {
          var action = x.state === 'adopt' ? adm(btn('Adopt', function () {
            call('adopt', { datastoreId: x.datastoreId }, true).then(loadStatus).catch(function (e) { alert(e.message); });
          })) || '' : '';
          return [x.name, x.storageServerId ? arrayName(x.storageServerId) : '', x.volume || '', gib(x.provisioned), x.blockDevice,
            el('span', { 'class': x.state === 'adopt' ? 'st-PASS' : 'st-WARN', text: x.detail }), action];
        })));
      }
      show('datastores', wrap);
    }

    function tenants(v) {
      call('datastore-tenants', { datastoreId: v.datastoreId }).then(function (d) {
        var list = d.tenants.map(function (t) { return t.tenantId + (t.tenantName ? ' ' + t.tenantName : '') + (t.tenantId === d.ownerId ? ' (owner)' : ''); }).join(', ');
        var ans = prompt(d.name + ' is shared with tenant(s): ' + (list || 'none') +
          '\n\nTo share it with another tenant, enter its tenant id. To stop sharing, enter -<id> (the owner cannot be removed).');
        if (!ans) return;
        ans = ans.trim();
        var remove = ans.charAt(0) === '-', id = ans.replace(/^-/, '');
        if (!/^\d+$/.test(id)) { alert('Enter a tenant id'); return; }
        call('datastore-share', { datastoreId: v.datastoreId, tenantId: id, op: remove ? 'remove' : 'add' }, true)
          .then(function (r) { alert((r.changed ? 'Done. ' : 'No change. ') + 'Shared with: ' + r.tenants.map(function (t) { return t.tenantId + (t.tenantName ? ' ' + t.tenantName : ''); }).join(', ')); })
          .catch(function (e) { alert(e.message); });
      }).catch(function (e) { alert(e.message); });
    }

    function loadSnapshots(v) {
      show('recovery', el('div', { 'class': 'muted', text: 'Loading snapshots of ' + v.name + '...' }));
      var sid = v.storageServerId;
      call('snapshots', { volume: v.name, storageServerId: sid }).then(function (list) {
        var hosts = el('select', {}); (state.status.hosts || []).forEach(function (h) { hosts.appendChild(el('option', { value: h, text: h })); });
        var take = btn('Take snapshot now', function () {
          var sfx = prompt('Optional suffix (letters, digits, -)') || '';
          call('snapshot-create', { volume: v.name, suffix: sfx, storageServerId: sid }, true).then(function () { loadSnapshots(v); }).catch(function (e) { alert(e.message); });
        });
        var rows = list.map(function (sn) {
          var open = btn('Open (read-only)', function () {
            if (confirm('Clone ' + sn.name + ' and mount it read-only on ' + hosts.value + '? Production is not touched.'))
              start('recovery-open', { volume: v.name, snapshot: sn.name, host: hosts.value, storageServerId: sid }, loadStatus);
          });
          var clone = state.status.experimental ? adm(btn('Clone as new datastore', function () {
            var n = prompt('Name for the new datastore');
            if (n) start('clone-datastore', { volume: v.name, snapshot: sn.name, name: n, host: hosts.value, storageServerId: sid }, loadStatus);
          })) : null;
          return [sn.name, sn.created ? new Date(sn.created).toLocaleString() : '', sn.protectionGroup || '', actions([open, clone])];
        });
        show('recovery', el('div', {}, [el('div', { 'class': 'row' }, [el('strong', { text: v.name + (state.status.links.length > 1 ? ' on ' + arrayName(sid) : '') }), ' Recovery host: ', hosts, take]),
          rows.length ? table(['Snapshot', 'Created', 'Protection group', ''], rows) : el('div', { 'class': 'muted', text: 'No snapshots.' })]));
      }).catch(function (e) { err('recovery', e); });
    }

    /**
     * Every open recovery session, one panel each. The section is redrawn when there are sessions, or when it still
     * shows sessions that have closed; a snapshot list the user opened stays put.
     */
    function renderRecoveries(s) {
      var panels = (s.recoveries || []).map(function (r) { return recoveryPanel(s, r); });
      var b = body('recovery');
      if (panels.length) show('recovery', el('div', { 'data-sessions': '1' }, panels));
      else if (b.querySelector('[data-sessions]')) show('recovery', el('div', { 'class': 'muted', text: 'Pick a datastore\'s snapshots above.' }));
    }

    function recoveryPanel(s, r) {
      var rows = (r.files || []).map(function (f) {
        return [f.path.replace(r.mount + '/', ''), gib(f.size), new Date(f.mtime * 1000).toLocaleString(), btn('Restore as new file', function () {
          var d = prompt('File name under <datastore>/restored/', f.path.split('/').pop().replace(/\.qcow2$/, '') + '-restored.qcow2');
          if (d) start('recovery-restore', { session: r.session, path: f.path, destName: d });
        })];
      });
      var vmBox = el('div', {});
      var listVms = btn('VMs in this snapshot', function () {
        lib.put(vmBox, el('div', { 'class': 'muted', text: 'Asking libvirt for VM disks...' }));
        call('recovery-vms', { session: r.session }).then(function (vms) {
          if (!vms.length) { lib.put(vmBox, el('div', { 'class': 'muted', text: 'No Morpheus VMs of this cluster have disks on this datastore.' })); return; }
          lib.put(vmBox, table(['VM', 'Power', 'Disks', 'In snapshot', ''], vms.map(function (vm) {
            var off = (vm.domainState || '').indexOf('shut off') >= 0;
            var b = btn('Restore in place', function () {
              if (confirm('Restore ' + vm.name + ' to ' + r.snapshot + '?\n\nEvery disk on this datastore is replaced with its snapshot copy. ' +
                  'The current disks are kept as .pre-restore-<time> files, and if any disk fails all are put back.'))
                start('recovery-restore-vm', { session: r.session, serverId: vm.serverId }, loadStatus);
            }, 'btn-danger');
            if (!off || !vm.restorable) b.disabled = true;
            return [vm.name, (vm.power || '?') + ' / ' + (vm.domainState || '?'),
              vm.disks.map(function (d) { return d.target + ' ' + d.path.split('/').slice(-2).join('/'); }).join(', '),
              vm.restorable ? 'yes' : 'missing ' + vm.disks.filter(function (d) { return !d.inSnapshot; }).map(function (d) { return d.target; }).join(','),
              off ? actions([b]) : el('div', {}, [actions([b]), el('div', { 'class': 'muted', text: 'Stop the VM first' })])];
          })));
        }).catch(function (e) { lib.put(vmBox, lib.errBox(e)); });
      });
      return el('div', {}, [el('div', { 'class': 'row' }, [el('strong', { text: r.snapshot }),
        (s.links.length > 1 ? ' (' + arrayName(r.storageServerId) + ')' : '') + ' mounted read-only on ' + r.host + ':' + r.mount,
        listVms,
        btn('Keep open' + (s.recoveryTimeoutHours ? ' (+' + s.recoveryTimeoutHours + ' h)' : ''), function () {
          call('recovery-extend', { session: r.session }, true).then(loadStatus).catch(function (e) { alert(e.message); });
        }),
        btn('Close session', function () { start('recovery-close', { session: r.session }, loadStatus); }, 'btn-warning'),
        el('span', { 'class': r.expires && r.expires - Date.now() < 3600000 ? 'st-WARN' : 'muted',
                     text: r.expires ? 'closes automatically ' + new Date(r.expires).toLocaleString() : 'no expiry recorded' })]),
        vmBox, rows.length ? table(['Disk file', 'Size', 'Modified', ''], rows) : el('div', { 'class': 'muted', text: 'No disk files in this snapshot.' })]);
    }

    // ------------------------------------------------------------------ retire a datastore

    function retire(v) {
      var label = (v.tags || {})['datastore-name'] || v.name;
      show('retire', el('div', { 'class': 'muted', text: 'Planning the retire of ' + label + ': reading its disks in Morpheus, the files on the datastore and the possible targets...' }));
      if (section('retire').scrollIntoView) section('retire').scrollIntoView({ behavior: 'smooth' });
      // the VM secure-metadata flag is only in Morpheus's API; the plan has the rest. If the user can't read the
      // datastore through the API, fall back to the plan's view (the empty-check still stops on NVRAM/TPM files).
      Promise.all([call('retire-plan', { datastoreId: v.datastoreId }),
                   mapi('GET', '/api/clusters/' + clusterId + '/datastores/' + v.datastoreId).then(function (j) { return j.datastore || {}; })
                     .catch(function (e) { return { unreadable: e.message }; })])
        .then(function (res) {
          var rec = res[1];
          if (rec.unreadable) rec = { heartbeatTarget: res[0].datastore.heartbeatTarget, unreadable: rec.unreadable };
          renderRetirePlan(v, res[0], rec);
        })
        .catch(function (e) { err('retire', e); });
    }

    function renderRetirePlan(v, p, rec) {
      var d = p.datastore, flags = [];
      if (rec.heartbeatTarget) flags.push('HA heartbeat target');
      if (rec.supportsVmSecureMetadata) flags.push('VM NVRAM/TPM state store');
      var wrap = el('div', {});
      wrap.appendChild(el('div', { 'class': 'row' }, [el('strong', { text: d.name }), ' LUN ' + (p.array.volume || '?') + (p.array.server ? ' on ' + p.array.server : '') +
        ', ' + gib(d.sizeBytes) + ' (' + gib(d.freeBytes) + ' free)' + (d.active === false ? ', already drained' : '') + (flags.length ? ' - ' + flags.join(', ') : '')]));
      var disks = [];
      p.vms.forEach(function (vm) { vm.disks.forEach(function (k) { disks.push([vm.name, vm.power || '?', k.name, gib(k.maxBytes), gib(k.usedBytes), vm.instanceId ? 'instance ' + vm.instanceId : 'server only']); }); });
      wrap.appendChild(disks.length ? table(['VM', 'Power', 'Disk', 'Size', 'Allocated', 'Moved via'], disks)
                                    : el('div', { 'class': 'muted', text: 'No Morpheus disks on this datastore.' }));
      (p.orphans || []).forEach(function (o) { wrap.appendChild(el('div', { 'class': 'st-WARN', text: 'Volume ' + o.name + ' (' + (o.path || '?') + ') belongs to no VM of this cluster' })); });
      var f = p.files || {};
      wrap.appendChild(el('div', { 'class': 'muted', text: 'On the filesystem' + (f.host ? ' (seen from ' + f.host + ')' : '') + ': ' + (f.known || []).length + ' Morpheus disk file(s), ' +
        (f.metadata || []).length + ' metadata file(s) (HA heartbeat, VM definition copies, restore-point index; removed with the datastore), ' +
        (f.leftovers || []).length + ' restore leftover(s), ' + (f.unknown || []).length + ' other file(s)' }));
      (f.unknown || []).slice(0, 20).forEach(function (x) { wrap.appendChild(el('div', { 'class': 'st-FAIL', text: '  not a Morpheus disk: ' + x.path + ' (' + gib(x.bytes) + ')' })); });
      (f.leftovers || []).forEach(function (x) { wrap.appendChild(el('div', { 'class': 'st-WARN', text: '  restore leftover: ' + x.path + ' (' + gib(x.bytes) + ')' })); });

      // target: Auto (most free space that fits) or a specific datastore
      var needTarget = disks.length > 0 || flags.length > 0;
      var sel = el('select', {});
      var auto = (p.targets || []).filter(function (t) { return t.id === p.auto; })[0];
      sel.appendChild(el('option', { value: '', text: auto ? 'Auto: ' + auto.name + ' (' + gib(auto.freeBytes) + ' free)' : 'Auto: no datastore fits' }));
      (p.targets || []).forEach(function (t) {
        var o = el('option', { value: t.id, text: t.name + ' - ' + gib(t.freeBytes) + ' free' + (t.fits ? '' : ' (' + t.reason + ')') });
        if (!t.fits) o.disabled = true;
        sel.appendChild(o);
      });
      var leftoverBox = el('input', { type: 'checkbox' });
      var blockers = (p.blockers || []).slice();
      if (flags.length && !auto && !(p.targets || []).some(function (t) { return t.fits; })) blockers.push('This datastore is the ' + flags.join(' and ') + '; another shared datastore must take that over first');
      if (needTarget) wrap.appendChild(el('div', { 'class': 'row' }, ['Move to: ', sel, el('span', { 'class': 'muted', text: 'needs ' + gib(p.need) + ' + 20% headroom' })]));
      if ((f.leftovers || []).length) wrap.appendChild(el('label', { 'class': 'row' }, [leftoverBox, ' Delete the restore leftovers with the datastore (the LUN stays recoverable in the destroyed bucket)']));
      if (rec.unreadable) wrap.appendChild(el('div', { 'class': 'st-WARN', text: 'Could not read this datastore through the Morpheus API as you, so whether it holds VM NVRAM/TPM state is unknown; the empty-check before deleting still stops on any such files.' }));
      blockers.forEach(function (b) { wrap.appendChild(el('div', { 'class': 'err', text: b })); });
      (p.warnings || []).forEach(function (w) { wrap.appendChild(el('div', { 'class': 'st-WARN', text: w })); });
      var log = el('pre', { text: '' });
      var go = btn('Retire ' + d.name, function () {
        var targetId = sel.value ? Number(sel.value) : p.auto;
        var target = (p.targets || []).filter(function (t) { return t.id === targetId; })[0];
        if (needTarget && !target) { alert('Choose a target datastore'); return; }
        if (!confirm('Retire ' + d.name + '?\n\n' + (flags.length ? '1. Hand ' + flags.join(' and ') + ' over to ' + target.name + '\n' : '') +
            '2. Drain it (no new disks)\n' + (disks.length ? '3. Move ' + disks.length + ' disk(s) of ' + p.vms.length + ' VM(s) to ' + target.name + ' with Morpheus Reconfigure (live; Morpheus drops VM snapshots of moved disks)\n' : '') +
            '4. Check it is empty, delete it in Morpheus\n5. Remove the LUN from every host, disconnect it and destroy it on ' + (p.array.server || 'the array') +
            ' (recoverable from the destroyed bucket until the array eradicates it)\n\nEach step stops on the first error; run Retire again to continue.')) return;
        go.disabled = true;
        runRetire(v, p, rec, target, leftoverBox.checked, function (s) { log.textContent += s + '\n'; })
          .then(function () { go.disabled = false; })
          .catch(function (e) { log.textContent += 'STOPPED: ' + e.message + '\n'; go.disabled = false; loadStatus(); });
      }, 'btn-danger');
      if (blockers.length) go.disabled = true;
      wrap.appendChild(actions([go, btn('Re-plan', function () { retire(v); })]));
      wrap.appendChild(log);
      show('retire', wrap);
    }

    var MOVE_TIMEOUT_MS = 30 * 60 * 1000;

    /** One VM's disks on src -> dst through Morpheus Reconfigure (instance level when it owns exactly this server). */
    function moveVm(vm, src, dst, say) {
      var byInstance = !!vm.instanceId;
      var getUrl = byInstance ? '/api/instances/' + vm.instanceId : '/api/servers/' + vm.serverId;
      var onSrc = function (x) { return String(x.datastoreId) === String(src); };
      return mapi('GET', getUrl).then(function (j) {
        var o = byInstance ? j.instance : j.server;
        if (byInstance && (o.servers || []).length > 1) { byInstance = false; getUrl = '/api/servers/' + vm.serverId; return mapi('GET', getUrl).then(function (k) { return k.server; }); }
        return o;
      }).then(function (o) {
        var moved = (o.volumes || []).filter(onSrc).length;
        if (!moved) { say('  ' + vm.name + ': nothing left on the source'); return null; }
        var vols = (o.volumes || []).map(function (x) { return lib.reconfVolume(x, onSrc(x) ? { datastoreId: dst } : {}); });
        say('  ' + vm.name + ': moving ' + moved + ' disk(s) (' + (byInstance ? 'instance' : 'server') + ' Reconfigure)...');
        var payload = { volumes: vols };
        payload[byInstance ? 'instance' : 'server'] = { plan: { id: o.plan && o.plan.id } };
        return mapi('PUT', getUrl + '/resize', payload).then(function () {
          return lib.waitFor(getUrl, function (x, secs) {
            var left = (x.volumes || []).filter(onSrc).length;
            if (!left && x.status !== 'resizing') { say('  ' + vm.name + ': done in ' + secs + ' s'); return true; }
            if (secs > 120 && x.status !== 'resizing' && left) throw new Error(vm.name + ': Morpheus finished the reconfigure but ' + left + ' disk(s) are still on the source (see the instance history)');
            if (secs % 30 < 5) say('  ' + vm.name + ': ' + x.status + ', ' + secs + ' s');
          }, vm.name + ' move', MOVE_TIMEOUT_MS);
        });
      });
    }

    function runRetire(v, p, rec, target, allowLeftovers, say) {
      var src = p.datastore.id;
      var chain = Promise.resolve();
      function then(f) { chain = chain.then(f); }
      // Datastore-record changes go through the plugin (in-process): a tenant user may read but not change the
      // datastores of a cloud the master tenant owns. VM moves below stay with the user (Reconfigure).
      function morph(action, extra) {
        return call('retire-morpheus', Object.assign({ op: action, datastoreId: src }, extra || {}), true).then(function (r) { say('  ' + r.done); });
      }
      if (rec.heartbeatTarget) then(function () { say('Making ' + target.name + ' an HA heartbeat target'); return morph('heartbeat-handover', { targetId: target.id }); });
      if (rec.supportsVmSecureMetadata) then(function () {
        say('Moving the VM NVRAM/TPM state designation to ' + target.name);
        return mapi('PUT', '/api/clusters/' + clusterId + '/datastores/' + target.id, { datastore: { supportsVmSecureMetadata: true } }).catch(function (e) {
          throw new Error('Could not move the VM NVRAM/TPM designation (' + e.message + ').' +
            (e.status === 403 ? ' Morpheus refused it for your user: Retire makes this change as you, so you need permission on that datastore itself (its Tenants in the edit dialog).' : '') +
            ' Set "VM secure metadata" on ' + target.name + ' in Morpheus, then run Retire again.');
        });
      });
      then(function () {
        if (p.datastore.active === false) { say('Already drained'); return; }
        say('Draining ' + p.datastore.name);
        return morph('drain');
      });
      p.vms.forEach(function (vm) { then(function () { return moveVm(vm, src, target.id, say); }); });
      then(function () {
        say('Checking the datastore is empty...');
        return call('retire-plan', { datastoreId: src }).then(function (q) {
          if (!q.empty) throw new Error('Morpheus still has ' + q.vms.length + ' VM(s) / ' + (q.orphans || []).length + ' other volume(s) on it');
          if ((q.files.unknown || []).length) throw new Error((q.files.unknown || []).length + ' file(s) that are not Morpheus disks are still on it, e.g. ' + q.files.unknown[0].path);
          if ((q.files.known || []).length) throw new Error((q.files.known || []).length + ' Morpheus disk file(s) are still on it, e.g. ' + q.files.known[0].path);
          if ((q.files.leftovers || []).length && !allowLeftovers) throw new Error((q.files.leftovers || []).length + ' restore leftover(s) remain; tick the box to delete them with the datastore, or remove them');
          say('  empty' + ((q.files.leftovers || []).length ? ' apart from ' + q.files.leftovers.length + ' restore leftover(s)' : ''));
        });
      });
      if (rec.heartbeatTarget) then(function () { say('Removing the heartbeat target flag from ' + p.datastore.name); return morph('heartbeat-clear'); });
      then(function () {
        say('Deleting ' + p.datastore.name + ' in Morpheus, then host and array cleanup (job in Operations)...');
        return new Promise(function (resolve, reject) {
          call('retire-finish', { datastoreId: src, volume: p.array.volume, storageServerId: p.array.storageServerId, allowLeftovers: allowLeftovers, deleteInMorpheus: true }, true)
            .then(function (j) {
              watch(j, function (done) {
                if (done.state === 'done') { say('Retired. ' + (done.progress || []).slice(-1)[0]); loadStatus(); resolve(); }
                else reject(new Error('array cleanup: ' + (done.error || done.state) + ' (Finish retire on the datastore row re-runs it)'));
              });
            }).catch(reject);
        });
      });
      return chain;
    }

    var HOST_STATE = {
      OK: ['st-PASS', 'In host group'], CREATE: ['st-FAIL', 'Not on the array'], ADD_TO_GROUP: ['st-WARN', 'On the array, not in the host group'],
      ADD_IQN: ['st-WARN', 'Array host missing an IQN'], REUSE: ['st-WARN', 'Array host has an old IQN (rebuilt host)'], CONFLICT: ['st-FAIL', 'In another host group'], UNKNOWN: ['st-FAIL', 'No IQN found']
    };
    var STORAGE_CLASS = { mounted: 'st-PASS', visible: 'st-WARN', missing: 'st-FAIL', 'other-path': 'st-WARN', unknown: 'st-NA' };

    /** Compliance results per host, with Remediate for the automatic fixes of everything that did not pass. */
    function renderCompliance(target, hosts) {
      lib.put(target, el('div', {}, hosts.reduce(function (out, h) {
        var fixes = []; h.results.forEach(function (r) { if (r.status !== 'PASS' && r.fix && fixes.indexOf(r.fix) < 0) fixes.push(r.fix); });
        var head = el('div', { 'class': 'row' }, [el('strong', { text: h.host }), ' Ubuntu ' + (h.os || '?') + ', kernel ' + (h.kernel || '?') + ' - ' + h.failed + ' failing',
          h.error ? el('span', { 'class': 'err', text: h.error }) : null]);
        if (fixes.length && adm(true)) head.appendChild(btn('Remediate (' + fixes.join(', ') + ')', function () {
          if (confirm('Apply on ' + h.host + ': ' + fixes.join(', ') + '?\nAdds drop-in config and missing sessions only; never logs out sessions.'))
            start('remediate', { host: h.host, fixes: fixes.join(',') });
        }, 'btn-warning'));
        return out.concat([head, table(['Check', 'Severity', 'Status', 'Observed', 'Expected'], h.results.map(function (r) {
          // a manual fix (hint) shows under what is expected
          return [r.id + ' ' + r.title, r.severity, el('span', { 'class': 'st-' + r.status, text: r.status }), r.observed,
            r.hint ? el('div', {}, [r.expected, el('div', { 'class': 'hint', text: r.hint })]) : r.expected];
        }))]);
      }, [])));
    }

    var PENDING = ['CREATE', 'ADD_TO_GROUP', 'ADD_IQN', 'REUSE'];

    /** Read-only: the array hosts that match this cluster's nodes (real IQN, stale Morpheus IQN or name) and what they see. */
    function showArrayHosts(target) {
      lib.put(target, el('div', { 'class': 'muted', text: 'Reading array hosts...' }));
      call('array-hosts').then(function (d) {
        lib.put(target, el('div', {}, d.arrays.reduce(function (out, a) {
          out.push(el('div', { 'class': 'row' }, [el('strong', { text: a.server || ('array ' + a.storageServerId) }), a.error ? el('span', { 'class': 'err', text: a.error }) : ' cluster host group: ' + (a.recordedHostGroup || 'not set')]));
          if (!a.hosts) return out;
          out.push(table(['Array host', 'IQNs', 'Host group', 'Matches', 'Volumes it sees', ''], a.hosts.map(function (x) {
            var m = x.matches.length ? el('span', { 'class': 'st-PASS', text: x.matches.join(', ') })
                  : (x.staleFor.length ? el('span', { 'class': 'st-FAIL', text: 'stale IQN of ' + x.staleFor.join(', ') }) : el('span', { 'class': 'st-WARN', text: 'name only' }));
            var rm = x.staleFor.length && !x.matches.length && !x.volumes.length && adm(true) ? btn('Remove', function () {
              if (confirm('Delete array host ' + x.name + ' (IQN ' + x.iqns.join(', ') + ', the stale Morpheus IQN of ' + x.staleFor.join(', ') + ') from ' + a.server +
                  (x.hostGroup ? ', and host group ' + x.hostGroup + ' if that leaves it empty' : '') + '? It sees no volumes.'))
                call('array-host-remove', { host: x.name, storageServerId: a.storageServerId }, true)
                  .then(function (r) { alert(r.done.join('\n')); showArrayHosts(target); loadStatus(); loadHosts(); })
                  .catch(function (e) { alert(e.message); });
            }, 'btn-danger') : null;
            return [x.name, x.iqns.join(', '), x.hostGroup || '(none)', m, x.volumes.map(function (v) { return v.volume + ' (LUN ' + v.lun + ', ' + v.via + ')'; }).join(', '), actions([rm])];
          })));
          return out;
        }, [])));
      }).catch(function (e) { lib.put(target, lib.errBox(e)); });
    }

    function loadHosts() {
      show('hostgroup', el('div', { 'class': 'muted', text: 'Reading the linked arrays and every host (IQN, LUNs, mounts)...' }));
      return call('hosts').then(function (hs) {
        var list = [];
        if (!hs.arrays.length) list.push(el('div', { 'class': 'muted', text: 'Link an array first.' }));
        // one line per array: its host group and the "add every host" action
        hs.arrays.forEach(function (a) {
          var pending = hs.hosts.filter(function (h) { return PENDING.indexOf(h.arrays.filter(function (x) { return x.storageServerId === a.storageServerId; })[0].action) >= 0; });
          var line = el('div', { 'class': 'row' }, [el('strong', { text: arrayName(a.storageServerId) }), ' host group ']);
          if (a.error) { line.appendChild(el('span', { 'class': 'err', text: a.error })); list.push(line); return; }
          line.appendChild(el('strong', { text: a.hostGroup || '(none yet)' }));
          if (a.recorded !== a.hostGroup) line.appendChild(document.createTextNode(' (not yet recorded on this cluster)'));
          if ((pending.length || a.recorded !== a.hostGroup) && adm(true)) {
            var apply = btn(pending.length ? 'Add all to host group (' + pending.length + ')' : 'Use host group ' + a.hostGroup, function () {
              if (confirm('On ' + arrayName(a.storageServerId) + ': apply host group ' + a.hostGroup + ' to every cluster host (create missing array hosts, add them to the group)?'))
                start('hostgroup-apply', { storageServerId: a.storageServerId, hostGroup: a.hostGroup }, function () { loadStatus(); loadHosts(); });
            }, 'btn-primary');
            apply.disabled = (a.blockers || []).length > 0;
            line.appendChild(apply);
          }
          list.push(line);
          (a.blockers || []).forEach(function (b) { list.push(el('div', { 'class': 'err', text: b })); });
          if ((a.strayHosts || []).length) list.push(el('div', { 'class': 'st-WARN', text: 'In ' + a.hostGroup + ' but not in this cluster (left untouched): ' + a.strayHosts.join(', ') }));
        });
        list.push(el('div', { 'class': 'muted', text: hs.datastores.length ? 'Pure GFS2 datastores: ' + hs.datastores.join(', ') : 'No Pure GFS2 datastores on this cluster' }));
        var detail = el('div', {});
        var rows = hs.hosts.map(function (h) {
          var cells = h.arrays.map(function (x) {
            var st = HOST_STATE[x.action] || ['st-NA', x.action];
            var add = PENDING.indexOf(x.action) >= 0 && adm(true) ? btn('Add', function () {
              if (confirm('Add ' + h.node + ' to its host group on ' + arrayName(x.storageServerId) + ', then rescan it so the cluster LUNs appear?'))
                start('host-add', { host: h.node, storageServerId: x.storageServerId }, function () { loadHosts(); });
            }, 'btn-primary') : null;
            return el('div', {}, [actions([el('span', { 'class': st[0], text: st[1], title: x.detail }), add]),
              x.arrayHost ? el('div', { 'class': 'muted', text: x.arrayHost }) : '']);
          });
          var storage = el('div', {}, h.storage.map(function (s) { return el('div', { 'class': STORAGE_CLASS[s.state] || '', text: s.name + ': ' + s.text }); }));
          var mount = h.storage.some(function (s) { return s.state === 'visible'; }) && adm(true) ? btn('Mount datastores', function () {
            if (confirm('Mount the visible-but-unmounted GFS2 datastores on ' + h.node + ', copying the mount and libvirt pool from a host that has them?'))
              start('host-mount', { host: h.node }, loadHosts);
          }, 'btn-warning') : null;
          var check = btn('Check', function () {
            lib.put(detail, el('div', { 'class': 'muted', text: 'Probing ' + h.node + '...' }));
            call('compliance', { host: h.node }).then(function (r) { renderCompliance(detail, r); }).catch(function (e) { lib.put(detail, lib.errBox(e)); });
          });
          var rescan = adm(btn('Rescan', function () { start('host-rescan', { host: h.node }, function () { loadHosts(); }); }));
          var arr = btn('Array view', function () { showArrayHosts(detail); });
          var name = el('div', {}, [el('div', { text: h.node }), el('div', { 'class': 'muted', text: h.iqn || 'no IQN' })]);
          if (h.iqnMismatch) {
            name.appendChild(el('div', { 'class': 'st-WARN', text: 'Morpheus record says ' + h.iqnRecord + ' (stale): the array must use the IQN above' }));
            name.appendChild(actions([adm(btn('Update Morpheus record', function () {
              call('iqn-sync', {}, true).then(function (r) { alert(r.changed.length ? r.changed.join('\n') : 'Already up to date'); loadHosts(); }).catch(function (e) { alert(e.message); });
            }))]));
          }
          return [name].concat(cells, [storage, actions([mount, rescan, check, arr])]);
        });
        var head = ['Host'].concat(hs.arrays.map(function (a) { return arrayName(a.storageServerId) + (a.hostGroup ? ' / ' + a.hostGroup : ''); }), ['Datastores on this host', '']);
        list.push(table(head, rows));
        list.push(detail);
        show('hostgroup', el('div', {}, list));
      }).catch(function (e) { err('hostgroup', e); });
    }

    /** The automatic check's last result, under the compliance section's buttons. */
    function renderAutoCompliance(s) {
      var c = s.compliance || {}, box = root.querySelector('[data-field="auto-compliance"]');
      if (!c.everyHours) { lib.put(box, el('span', { 'class': 'muted', text: 'Automatic check is off (plugin setting).' })); return; }
      var head = 'Checked automatically every ' + c.everyHours + ' h; failing hosts get a Morpheus alarm (Operations > Alarms).';
      if (!c.at && !(c.hosts || []).length) { lib.put(box, el('span', { 'class': 'muted', text: head + ' Not run yet since the plugin loaded.' })); return; }
      lib.put(box, el('div', {}, [el('div', { 'class': 'muted', text: head + (c.at ? ' Last full check ' + new Date(c.at).toLocaleString() + ':' : '') }),
        el('div', {}, (c.hosts || []).map(function (h) {
          return el('div', { 'class': h.error ? 'st-WARN' : (h.failed ? 'st-FAIL' : 'st-PASS'),
            text: h.host + ': ' + (h.error ? h.error : (h.failed ? h.failed + ' failing (' + h.checks.filter(function (c, i) { return h.checks.indexOf(c) === i; }).join(', ') + ')' : 'passes')) });
        }))]));
    }

    function hhmm(ms) { return ms ? new Date(ms).toLocaleString() : ''; }

    /** Read-only: the array's protection groups over each datastore, and SafeMode. Set on the array, never here. */
    function loadPolicies() {
      show('policies', el('div', { 'class': 'muted', text: 'Reading protection groups from the linked arrays...' }));
      call('snapshot-policies').then(function (arrays) {
        show('policies', el('div', {}, arrays.reduce(function (out, a) {
          var sm = a.safeMode || {};
          out.push(el('div', { 'class': 'row' }, [el('strong', { text: a.server || arrayName(a.storageServerId) }),
            a.error ? el('span', { 'class': 'err', text: a.error })
              : el('span', { 'class': sm.on ? 'st-PASS' : 'muted', text: !sm.reported ? 'Array-wide SafeMode: not reported by this Purity version'
                : 'Array-wide SafeMode ' + (sm.on ? 'on' : 'off') + ' (manual eradication ' + (sm.manualEradication || '?') + (sm.eradicationDelay ? ', eradication delay ' + sm.eradicationDelay : '') +
                  '). A protection group with its retention lock ratcheted is SafeMode-protected either way.' })]));
          if (a.error) return out;
          if (!(a.volumes || []).length) { out.push(el('div', { 'class': 'muted', text: 'No datastore volumes of this cluster on this array.' })); return out; }
          var rows = [];
          a.volumes.forEach(function (v) {
            var label = (v.datastore || '') + (v.datastore ? ' / ' : '') + v.volume;
            if (!v.policies.length) rows.push([label, el('span', { 'class': 'st-FAIL', text: 'none' }), '', '', '', '', '', '']);
            v.policies.forEach(function (pg) {
              rows.push([label, pg.name, pg.via.join(', '), pg.scheduled ? 'every ' + pg.every : el('span', { 'class': 'st-WARN', text: 'not scheduled' }),
                pg.retention, pg.replication || 'none', el('span', { 'class': pg.safeMode ? 'st-PASS' : 'muted', text: pg.safeMode ? 'locked (ratcheted)' : (pg.retentionLock || 'unlocked') }),
                hhmm(pg.lastSnapshot)]);
            });
          });
          out.push(table(['Datastore / volume', 'Protection group', 'Covers it through', 'Snapshots', 'Kept on this array', 'Replication', 'Retention lock', 'Last snapshot'], rows));
          a.volumes.filter(function (v) { return !v.scheduled; }).forEach(function (v) {
            out.push(el('div', { 'class': 'st-WARN', text: (v.datastore || v.volume) + ': no scheduled snapshots, so customers only get restore points someone takes by hand.' }));
          });
          return out;
        }, [])));
      }).catch(function (e) { err('policies', e); });
    }

    /** Restore leftovers (deletable with Administration), other files and detached Morpheus disks, per GFS2 datastore. */
    function loadReclaim() {
      show('reclaim', el('div', { 'class': 'muted', text: 'Listing every GFS2 datastore and asking each host\'s libvirt which files are in use...' }));
      call('reclaim-scan').then(function (list) {
        if (!list.length) { show('reclaim', el('div', { 'class': 'muted', text: 'No GFS2 datastores on this cluster.' })); return; }
        show('reclaim', el('div', {}, list.map(renderReclaim)));
      }).catch(function (e) { err('reclaim', e); });
    }

    function renderReclaim(d) {
      var wrap = el('div', {});
      wrap.appendChild(el('div', { 'class': 'row' }, [el('strong', { text: d.name }),
        d.mounted ? gib(d.reclaimableBytes) + ' reclaimable in ' + d.leftovers.filter(function (f) { return !f.inUse; }).length + ' restore leftover(s)' +
          (d.freeBytes ? ' (' + gib(d.freeBytes) + ' free now)' : '') : el('span', { 'class': 'st-WARN', text: 'not mounted on any host' })]));
      var boxes = [];
      if (d.leftovers.length) {
        wrap.appendChild(table(['', 'Restore leftover', 'Kind', 'Size', 'Since', ''], d.leftovers.map(function (f) {
          var cb = el('input', { type: 'checkbox', value: f.path });
          if (f.inUse) cb.disabled = true; else boxes.push(cb);
          return [state.status.canAdmin === false ? '' : cb, f.path, f.kind, gib(f.bytes), hhmm(f.since || f.mtime),
            f.inUse ? el('span', { 'class': 'st-WARN', text: 'in use by a VM' }) : ''];
        })));
        var del = adm(btn('Delete selected', function () {
          var picked = boxes.filter(function (b) { return b.checked; }).map(function (b) { return b.value; });
          if (!picked.length) { alert('Tick the files to delete'); return; }
          if (confirm('Delete ' + picked.length + ' file(s) from ' + d.name + '?\n\n' + picked.join('\n') +
              '\n\nA .pre-restore file is the disk an in-place restore replaced: deleting it removes the way back to that disk. ' +
              'Each file is checked again on every host first; anything a VM uses is skipped. This cannot be undone.'))
            start('reclaim-delete', { datastoreId: d.datastoreId, paths: picked.join(',') }, loadReclaim);
        }, 'btn-danger'));
        if (del && boxes.length) wrap.appendChild(actions([btn('Select all', function () { boxes.forEach(function (b) { b.checked = true; }); }), del]));
      } else if (d.mounted) {
        wrap.appendChild(el('div', { 'class': 'muted', text: 'No restore leftovers.' }));
      }
      if (d.error) wrap.appendChild(el('div', { 'class': 'err', text: d.error }));
      (d.detachedDisks || []).forEach(function (v) {
        wrap.appendChild(el('div', { 'class': 'st-WARN', text: 'Morpheus disk ' + v.name + ' (' + (v.path || '?') + ', ' + gib(v.maxBytes) + ') belongs to no VM of this cluster: delete it in Morpheus if nobody needs it' }));
      });
      if (d.otherCount) {
        wrap.appendChild(el('div', { 'class': 'muted', text: d.otherCount + ' other file(s), ' + gib(d.otherBytes) + ', that are not Morpheus disks (shown only; they may be ISOs or someone\'s data)' + (d.truncated ? '; the listing was capped' : '') + ':' }));
        d.otherFiles.forEach(function (f) { wrap.appendChild(el('div', { 'class': 'muted', text: '  ' + f.path + ' (' + gib(f.bytes) + ')' })); });
      }
      return wrap;
    }

    function onAction(act) {
      if (act === 'policies') {
        loadPolicies();
      } else if (act === 'reclaim') {
        loadReclaim();
      } else if (act === 'plan') {
        loadHosts();
      } else if (act === 'compliance') {
        show('compliance', el('div', { 'class': 'muted', text: 'Probing hosts...' }));
        call('compliance').then(function (hosts) { renderCompliance(body('compliance'), hosts); loadStatus(); }).catch(function (e) { err('compliance', e); });
      } else if (act === 'create') {
        var n = root.querySelector('[data-field="ds-name"]').value.trim(), g = root.querySelector('[data-field="ds-size"]').value;
        var sid = root.querySelector('[data-field="ds-array"]').value;
        if (!n || !g) { alert('Name and size are required'); return; }
        if (confirm('Create a ' + g + ' GiB volume "' + n + '" on ' + arrayName(sid) + ', present it to every host and create a GFS2 datastore?'))
          start('datastore-create', { name: n, sizeGiB: g, storageServerId: sid }, loadStatus);
      }
    }

    root.addEventListener('click', function (ev) {
      var t = ev.target.closest ? ev.target.closest('[data-act]') : null;
      if (t && root.contains(t)) onAction(t.getAttribute('data-act'));
    });
    loadStatus();
  }

  lib.register('.pure-hvm[data-cluster-id]', 'clusterTab', init);
})();
