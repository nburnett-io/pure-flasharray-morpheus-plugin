//= require pure-common
/*
 * Pure FlashArray for HVM - instance "Restore" tab: restore this VM, or part of it, to an earlier point in time.
 *   Whole VM / chosen disks  - the VM (stopped) gets those disks back as they were; the replaced disks are kept.
 *   File recovery            - a temporary copy of one disk is attached to the running VM; copy files in the guest.
 *   Add as a new disk        - a copy of one disk is added to the VM (stopped) as a permanent extra disk.
 *   Clone to new VM          - a new VM is provisioned through Morpheus with the same layout, stopped, and given the
 *                              restore point's disks; the original VM is not touched.
 * Restore points are times only: the server hands out opaque ids, never snapshot or storage names.
 * Morpheus steps (stop / provision / add disk / start) run as the logged-in user through Morpheus's own /api; the disk
 * work runs as a plugin job (/plugin/pure-hvm/vm-*), which re-checks that the VM belongs to the user's tenant.
 */
(function () {
  'use strict';
  var lib = window.Morpheus.pluginTabs.pureFlashArrayHvm.lib;
  var el = lib.el, btn = lib.btn, gib = lib.gib, mapi = lib.mapi;

  function init(root) {
    if (!root || root.getAttribute('data-pure-init')) return;
    root.setAttribute('data-pure-init', '1');
    var instanceId = root.getAttribute('data-instance-id');
    var state = {};
    var call = lib.client('instanceId', instanceId, 'Your role does not include "Pure FlashArray VM Restore". Ask your administrator.');

    function body(id) { return root.querySelector('[data-section="' + id + '"] .ph-body'); }
    function show(id, node) { lib.put(body(id), node); }
    function toggle(id, visible) { root.querySelector('[data-section="' + id + '"]').classList.toggle('ph-hidden', !visible); }
    function pad(n) { return (n < 10 ? '0' : '') + n; }
    /** "Today 14:05", "Yesterday 02:00", "Mon 19 Oct 2026 02:00" - local time. */
    function when(ms) {
      if (!ms) return 'unknown time';
      var d = new Date(ms), now = new Date(), hm = pad(d.getHours()) + ':' + pad(d.getMinutes());
      var day = new Date(d.getFullYear(), d.getMonth(), d.getDate()), today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
      var diff = Math.round((today - day) / 86400000);
      if (diff === 0) return 'Today ' + hm;
      if (diff === 1) return 'Yesterday ' + hm;
      return d.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }) + ' ' + hm;
    }
    /** yyyymmdd (withTime: yyyymmddhhmm), local time, for default names. */
    function stampOf(ms, withTime) {
      var t = new Date(ms || Date.now());
      return t.getFullYear() + pad(t.getMonth() + 1) + pad(t.getDate()) + (withTime ? pad(t.getHours()) + pad(t.getMinutes()) : '');
    }
    function diskLabel(x) { return x.name + ' (' + x.device + ', ' + gib(x.sizeBytes) + ')'; }
    function diskById(id) { return state.vm.disks.filter(function (x) { return String(x.id) === String(id); })[0]; }

    var log;
    function say(s) { if (!log) { log = el('pre', {}); show('progress', log); } log.textContent += s + '\n'; }
    /** Disables every control while something runs; controls marked data-off (not possible right now) stay disabled. */
    function busy(on) { Array.prototype.forEach.call(root.querySelectorAll('button, input, select'), function (b) { b.disabled = on || b.hasAttribute('data-off'); }); }
    /**
     * Runs one action: controls disabled, a fresh progress log titled {@code title}, then the page reloads. On failure
     * "STOPPED: <why>" plus {@code onFail()}'s text, and the page stays as it is.
     */
    function run(title, work, onFail, reload) {
      busy(true); log = null; say(title);
      return work().then(function () { busy(false); if (reload !== false) load(); },
        function (e) { say('STOPPED: ' + e.message + (onFail ? onFail() : '')); busy(false); });
    }

    /** Follows a restore job, printing its new progress lines; rejects unless it ends done. */
    function watchJob(job) {
      var seen = 0;
      return lib.followJob(call, 'vm-job', job, {}, function (j) {
        (j.progress || []).slice(seen).forEach(say); seen = (j.progress || []).length;
      }).then(function (j) {
        if (j.state !== 'done') throw new Error(j.error || j.state);
        return j;
      });
    }
    function power(action, id, name) {
      id = id || instanceId;
      say((action === 'stop' ? 'Stopping ' : 'Starting ') + name + '...');
      var wanted = action === 'stop' ? 'stopped' : 'running';
      return mapi('PUT', '/api/instances/' + id + '/' + action)
        .then(function () { return lib.waitFor('/api/instances/' + id, function (o) { return o.status === wanted ? o : undefined; }, action + ' ' + name, 10 * 60000); })
        .then(function () { say('  ' + wanted); });
    }

    // ------------------------------------------------------------------ page

    function load() {
      show('vm', el('div', { 'class': 'muted', text: 'Reading this VM...' }));
      toggle('action', false);
      call('vm-restore-points').then(function (d) {
        state.vm = d;
        var p = (d.power || '').toLowerCase();
        state.off = p === 'off'; state.on = p === 'on';
        var offNet = d.network && d.network !== 'connected';
        show('vm', el('div', {}, [
          el('div', { text: d.name + ' - ' + d.disks.map(diskLabel).join(', ') }),
          el('div', { 'class': 'muted', text: 'Power: ' + (d.power || '?') }),
          el('div', { 'class': offNet ? 'warn' : 'muted', text: 'Network: ' + (d.network || 'unknown') + (offNet ? ' - the VM is kept off the network' : '') }),
          lib.actions([
            offNet ? btn('Connect network', function () {
              if (!confirm('Connect ' + d.name + ' to its network now?\n\nIf this is a restored copy of another VM that is still running, both have the same name and identity on the network.')) return;
              run('Connecting ' + d.name + ' to its network...', function () { return call('vm-network', { connect: 'true' }, true).then(function () { say('  connected'); }); });
            }, 'btn-warning') : null,
            !state.off ? btn('Stop VM', function () {
              if (confirm('Stop ' + d.name + ' now?')) run('Stop ' + d.name, function () { return power('stop', null, d.name); });
            }) : null,
            state.off ? btn('Start VM', function () { run('Start ' + d.name, function () { return power('start', null, d.name); }); }) : null])]));
        renderCopies(d);
        var schedule = d.schedule ? el('div', { 'class': 'muted', text: d.schedule }) : null;
        if (!d.points.length) { show('points', el('div', {}, [el('div', { 'class': 'muted', text: 'No restore points of this VM yet. They are taken by your provider\'s schedule.' }), schedule])); return; }
        show('points', el('div', {}, [schedule, lib.table(['Restore point', 'Type', 'Holds', ''], d.points.map(function (pt) {
          var holds = pt.coverage === 'partial' ? 'disk ' + pt.disks.map(function (i) { return diskById(i).name; }).join(', ') + ' only'
            : pt.coverage === 'unverified' ? 'not checked yet' : 'the whole VM';
          return [when(pt.created), el('span', { 'class': 'muted', text: pt.kind || '' }), el('span', { 'class': pt.coverage === 'full' ? 'muted' : 'warn', text: holds }),
            btn('Restore...', function () { choose(pt); }, 'btn-primary')];
        }))]));
      }).catch(function (e) { show('vm', lib.errBox(e)); });
    }

    function renderCopies(d) {
      var list = d.copies || [];
      toggle('copies', list.length > 0);
      if (!list.length) return;
      show('copies', lib.table(['Copy', 'State', 'Removal', ''], list.map(function (c) {
        var keepSel = hoursSelect(d.copyHours);
        return ['Disk ' + (c.fromDisk || '?') + ' as of ' + when(c.restoredTo),
          el('span', { 'class': 'muted', text: (c.attachedAs ? 'attached as ' + c.attachedAs : 'not attached (the VM was powered off since; removed on the next sweep)') + ', ' + gib(c.sizeBytes) }),
          el('span', { 'class': 'warn', text: c.expires ? 'removed automatically ' + when(c.expires) : '' }),
          lib.actions([keepSel, btn('Keep', function () {
            run('Keeping the copy of disk ' + c.fromDisk + ' for ' + keepSel.value + ' h from now...', function () {
              return call('vm-file-restore-extend', { copy: c.copy, hours: keepSel.value }, true).then(function (r) { say('  kept until ' + when(r.expires)); });
            });
          }), btn('Remove', function () {
            if (!confirm('Remove the copy of disk ' + c.fromDisk + ' as of ' + when(c.restoredTo) + '?' + (c.attachedAs ? '\n\nUnmount it (or set it offline) in the guest first.' : ''))) return;
            run('Removing the copy of disk ' + c.fromDisk + '...', function () { return call('vm-file-restore-remove', { copy: c.copy }, true).then(function () { say('  removed'); }); });
          })])];
      })));
    }

    /** "Keep for N hours" choices up to the provider's maximum (default 24, max 72), the default selected. */
    function hoursSelect(h) {
      h = h || { 'default': 24, max: 72 };
      var opts = [4, 8, 24, 48, 72].filter(function (n) { return n <= h.max; });
      if (opts.indexOf(h['default']) < 0) opts.push(h['default']);
      if (opts.indexOf(h.max) < 0) opts.push(h.max);
      opts.sort(function (a, b) { return a - b; });
      return el('select', { 'class': 'input-sm' }, opts.map(function (n) {
        var o = el('option', { value: String(n), text: n + ' h' });
        if (n === h['default']) o.setAttribute('selected', 'selected');
        return o;
      }));
    }

    /** The options for one restore point. */
    function choose(pt) {
      var d = state.vm;
      toggle('action', true);
      root.querySelector('[data-section="action"] h3').textContent = 'Restore to ' + when(pt.created);
      var name = 'pure-mode-' + instanceId;
      function opt(value, label, enabled, why, extra) {
        var r = el('input', { type: 'radio', name: name, value: value });
        if (!enabled) {
          r.disabled = true; r.setAttribute('data-off', '');
          if (extra) Array.prototype.forEach.call(extra.matches('input, select') ? [extra] : extra.querySelectorAll('input, select'), function (c) { c.disabled = true; c.setAttribute('data-off', ''); });
        }
        return el('div', {}, [el('label', { 'class': 'opt' + (enabled ? '' : ' muted') }, [r, ' ' + label + (enabled || !why ? '' : ' - ' + why)]), extra]);
      }
      // only the disks this restore point holds can be restored from it
      var held = d.disks.filter(function (x) { return (pt.disks || []).some(function (i) { return String(i) === String(x.id); }); });
      var whole = pt.coverage !== 'partial';
      var checks = el('div', { 'class': 'sub' }, held.map(function (x) {
        return el('label', { 'class': 'opt' }, [el('input', { type: 'checkbox', value: String(x.id), checked: 'checked' }), ' ' + diskLabel(x)]);
      }));
      function diskSelect() {
        return el('select', { 'class': 'sub' }, held.map(function (x) { return el('option', { value: String(x.id), text: diskLabel(x) }); }));
      }
      var fileSel = diskSelect(), addSel = diskSelect(), fileHours = hoursSelect(d.copyHours);
      var fileOpts = el('div', { 'class': 'sub' }, [fileSel, el('label', { 'class': 'opt' }, ['Keep it attached for ', fileHours,
        ' (then it is detached and deleted automatically; you can keep it longer, up to ' + ((d.copyHours && d.copyHours.max) || 72) + ' h from then)'])]);
      var cloneName = el('input', { type: 'text', 'class': 'form-control input-sm', value: d.name + '-restored-' + stampOf(pt.created, false), maxlength: '63' });
      var powerOn = el('input', { type: 'checkbox' }), connectNet = el('input', { type: 'checkbox' });
      var cloneOpts = el('div', { 'class': 'sub' }, [
        el('label', { 'class': 'opt' }, ['New VM name ', cloneName]),
        el('label', { 'class': 'opt' }, [powerOn, ' Power on automatically']),
        el('label', { 'class': 'opt' }, [connectNet, ' Connect to network automatically'])]);
      var stopped = 'stop the VM first', running = 'start the VM first', partial = 'this restore point does not hold every disk';
      var form = el('div', {}, [
        opt('vm', 'Overwrite the whole VM', state.off && whole, whole ? stopped : partial),
        opt('disks', 'Overwrite chosen disks only', state.off && held.length > 0 && (held.length > 1 || !whole), held.length > 1 || !whole ? stopped : 'the VM has one disk', checks),
        opt('file', 'File recovery: attach a temporary copy of a disk to the running VM, then copy files in the guest', state.on && held.length > 0, running, fileOpts),
        opt('add', 'Add a copy of a disk to the VM as a new permanent disk', state.off && held.length > 0, stopped, addSel),
        opt('clone', 'Clone to a new VM (this VM is not touched)', whole, partial, cloneOpts)
      ]);
      var note = el('div', { 'class': 'warn note' });
      var notes = {
        vm: 'Everything written to ' + d.name + ' since ' + when(pt.created) + ' is replaced. The replaced disks are kept until your provider removes them.',
        disks: 'Only the ticked disks go back to ' + when(pt.created) + '; the others keep their current contents.',
        file: 'The copy has the same disk identity as the original. Windows: in Disk Management bring it Online. Linux: mount it by device, read-only, e.g. "mount -o ro /dev/vdX1 /mnt/restore" (XFS: add nouuid; LVM: vgimportclone first). It is not in fstab and leaves the VM when the VM powers off; remove it here when done.',
        add: 'Morpheus adds a new disk to ' + d.name + ' and it receives the copy. Linux: the copy has the same filesystem UUIDs and labels as the original, so a copy of a boot disk can be booted from by mistake - use File recovery for that. Windows brings it up Offline (same signature) until you set it Online.',
        clone: 'A new VM with the same size and disks as ' + d.name + ' is created, then given the disks as of ' + when(pt.created) + '. ' +
          'It carries ' + d.name + '\'s identity (computer name, domain membership, static IPs, services): unless you tick "Connect to network", its network stays disconnected until you connect it from its own Restore tab.'
      };
      function mode() { return (root.querySelector('input[name="' + name + '"]:checked') || {}).value; }
      form.addEventListener('change', function () { note.textContent = notes[mode()] || ''; });
      var first = form.querySelector('input[type=radio]:not([disabled])');
      if (first) first.checked = true;
      note.textContent = notes[mode()] || '';
      show('action', el('div', {}, [form, note, lib.actions([
        btn('Run', function () {
          var m = mode();
          if (m === 'vm') overwrite(pt, null);
          else if (m === 'disks') {
            var ids = Array.prototype.filter.call(checks.querySelectorAll('input'), function (c) { return c.checked; }).map(function (c) { return c.value; });
            if (!ids.length) { alert('Tick at least one disk'); return; }
            overwrite(pt, ids);
          } else if (m === 'file') fileRestore(pt, fileSel.value, fileHours.value);
          else if (m === 'add') addDisk(pt, addSel.value);
          else if (m === 'clone') clone(pt, cloneName.value.trim(), powerOn.checked, connectNet.checked);
        }, 'btn-danger'),
        btn('Cancel', function () { toggle('action', false); })])]));
    }

    function overwrite(pt, ids) {
      var d = state.vm;
      var what = ids ? 'disk(s) ' + ids.map(function (i) { return diskById(i).name; }).join(', ') + ' of ' + d.name : d.name;
      if (!confirm('Overwrite ' + what + ' with its contents as of ' + when(pt.created) + '?\n\nEverything written since then is replaced. ' +
          'The replaced disks are kept until your provider removes them. The VM stays stopped; start it afterwards.')) return;
      run('Overwrite ' + what + ' to ' + when(pt.created), function () {
        return call('vm-restore', { restorePoint: pt.id, volumeIds: ids ? ids.join(',') : null }, true).then(watchJob)
          .then(function () { say('Done. Start the VM to use it.'); });
      });
    }

    function fileRestore(pt, diskId, hours) {
      run('File recovery: disk ' + diskById(diskId).name + ' as of ' + when(pt.created) + ', attached for ' + hours + ' h', function () {
        return call('vm-file-restore', { restorePoint: pt.id, sourceVolumeId: diskId, hours: hours }, true).then(watchJob).then(function (j) {
          say('Done. In the guest, look for the new disk (' + ((j.result && j.result.attachedAs) || 'the next free disk') + '). ' +
              'It is removed automatically ' + when(j.result && j.result.expires) + '.');
        });
      });
    }

    function addDisk(pt, diskId) {
      var d = state.vm, x = diskById(diskId);
      var volName = 'restored-' + x.name.replace(/[^A-Za-z0-9-]/g, '') + '-' + stampOf(pt.created, true);
      if (!confirm('Add disk "' + volName + '" to ' + d.name + ' holding disk ' + x.name + ' as of ' + when(pt.created) + '?')) return;
      var newVol;
      run('Add disk ' + x.name + ' as of ' + when(pt.created) + ' to ' + d.name + ' as ' + volName, function () {
        return mapi('GET', '/api/instances/' + instanceId).then(function (j) {
          var inst = j.instance, src = (inst.volumes || []).filter(function (v) { return String(v.id) === String(diskId); })[0];
          if (!src) throw new Error('disk ' + x.name + ' not found on the instance');
          var vols = (inst.volumes || []).map(function (v) { return lib.reconfVolume(v); });
          vols.push(lib.reconfVolume(src, { id: -1, rootVolume: false, name: volName }));
          say('Adding an empty ' + src.size + ' GB disk through Morpheus...');
          return mapi('PUT', '/api/instances/' + instanceId + '/resize', { instance: { plan: { id: inst.plan && inst.plan.id } }, volumes: vols });
        }).then(function () {
          return lib.waitFor('/api/instances/' + instanceId, function (o) {
            var v = (o.volumes || []).filter(function (v) { return v.name === volName; })[0];
            return v && o.status !== 'resizing' ? v : undefined;
          }, 'adding the disk', 10 * 60000);
        }).then(function (v) {
          newVol = v;
          say('  added (disk ' + v.name + '); copying the restore point into it...');
          return call('vm-add-disk', { restorePoint: pt.id, sourceVolumeId: diskId, newVolumeId: v.id }, true).then(watchJob);
        }).then(function () { say('Done. Start the VM to use the new disk.'); });
      }, function () {
        return newVol ? ' (the new, empty disk ' + newVol.name + ' is still on the VM; remove it with Reconfigure if you do not want it)' : '';
      });
    }

    /** New VM like this one: same cloud, group, layout, plan, image, cluster, disks (sizes, datastore) and networks. */
    function clonePayload(src, name) {
      var cfg = src.config || {};
      return {
        zoneId: src.cloud.id,
        instance: { name: name, site: { id: src.group.id }, instanceType: { code: src.instanceType.code }, layout: { id: src.layout.id }, plan: { id: src.plan.id } },
        config: { imageId: cfg.imageId, resourcePoolId: cfg.resourcePoolId, createUser: cfg.createUser, noAgent: cfg.noAgent },
        volumes: (src.volumes || []).filter(function (v) { return v.volumeCategory !== 'cd' && v.storageType !== 2; })
          .map(function (v) { return lib.reconfVolume(v, { id: -1 }); }),
        networkInterfaces: (src.interfaces || []).map(function (i) { return { network: { id: 'network-' + i.network.id } }; })
      };
    }

    /**
     * The new VM is provisioned (a fresh template, so its first boot is harmless), stopped, given the restore point's
     * disks - with its network links down unless connectNet - and only started when powerOn.
     */
    function clone(pt, name, powerOn, connectNet) {
      var d = state.vm;
      if (!/^[A-Za-z0-9][A-Za-z0-9.-]{0,62}$/.test(name || '')) { alert('VM name: letters, digits, - and . (up to 63)'); return; }
      if (!confirm('Create ' + name + ' with ' + d.name + '\'s disks as of ' + when(pt.created) + '?\n\n' +
          (connectNet ? 'Its network will be CONNECTED: it has the same identity as ' + d.name + '.' : 'Its network stays disconnected until you connect it.') + '\n' +
          (powerOn ? 'It is started when ready.' : 'It is left stopped.'))) return;
      var newId;
      run('Clone ' + d.name + ' as of ' + when(pt.created) + ' into a new VM ' + name, function () {
        return mapi('GET', '/api/instances/' + instanceId).then(function (j) {
          say('Creating ' + name + ' (same size, disks and network as ' + d.name + ')...');
          return mapi('POST', '/api/instances', clonePayload(j.instance, name));
        }).then(function (j) {
          newId = j.instance.id;
          say('  waiting for ' + name + ' to finish provisioning...');
          return lib.waitFor('/api/instances/' + newId, function (o) { return o.status === 'running' ? o : undefined; }, 'provisioning ' + name, 30 * 60000);
        }).then(function () {
          return power('stop', newId, name);
        }).then(function () {
          return call('vm-clone', { targetInstanceId: newId, restorePoint: pt.id, connectNetwork: connectNet ? 'true' : 'false' }, true).then(watchJob);
        }).then(function () {
          return powerOn ? power('start', newId, name) : null;
        }).then(function () {
          say('Done.' + (powerOn ? '' : ' ' + name + ' is stopped; start it when you are ready.') +
              (connectNet ? '' : ' Its network is disconnected; connect it from its own Restore tab.'));
          show('action', el('div', {}, [el('span', { 'class': 'ok', text: name + ' was created from ' + d.name + ' as of ' + when(pt.created) + '. ' }),
            el('a', { href: '/provisioning/instances/' + newId, text: 'Open ' + name })]));
        });
      }, function () {
        return newId ? ' (the new VM ' + name + ' was created; delete it if you do not need it)' : '';
      }, false);
    }

    load();
  }

  lib.register('.pure-hvm-vm[data-instance-id]', 'instanceTab', init);
})();
