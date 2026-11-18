'use strict';

const REFRESH_MS = 5000;
const state = { info: null, tab: 'overview', selectedWorker: null, timer: null };

// ---------- helpers ----------
const $ = (sel) => document.querySelector(sel);

function esc(v) {
    return String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function bytes(n) {
    if (n == null || n < 0) return '–';
    const u = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
    return `${n.toFixed(n >= 10 || i === 0 ? 0 : 1)} ${u[i]}`;
}

function pct(v) { return v == null || v < 0 ? '–' : `${v.toFixed(1)}%`; }

function ago(iso) {
    if (!iso) return 'never';
    const s = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
    if (s < 60) return `${s}s ago`;
    if (s < 3600) return `${Math.floor(s / 60)}m ago`;
    if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
    return `${Math.floor(s / 86400)}d ago`;
}

function duration(ms) {
    const s = Math.floor(ms / 1000), d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60);
    return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : `${m}m ${s % 60}s`;
}

function bar(percent) {
    if (percent == null || percent < 0) return '<div class="bar"><span style="width:0"></span></div>';
    const p = Math.min(100, Math.max(0, percent));
    const cls = p >= 90 ? 'bad' : p >= 70 ? 'warn' : '';
    return `<div class="bar ${cls}"><span style="width:${p}%"></span></div>`;
}

function usedPct(total, free) { return total > 0 ? (total - free) / total * 100 : -1; }

function mainDisk(r) {
    if (!r?.disks?.length) return null;
    return r.disks.reduce((a, b) => (b.totalBytes > a.totalBytes ? b : a));
}

async function api(method, url, body) {
    const res = await fetch(url, {
        method,
        headers: body ? { 'Content-Type': 'application/json' } : {},
        body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
        let msg = `${res.status} ${res.statusText}`;
        try {
            const p = await res.json();
            msg = p.detail || p.message || msg;
        } catch { /* not json */ }
        throw new Error(msg);
    }
    return res.status === 204 ? null : res.json();
}

function toast(msg, isError = false) {
    const t = $('#toast');
    t.textContent = msg;
    t.className = 'toast' + (isError ? ' error' : '');
    clearTimeout(toast.h);
    toast.h = setTimeout(() => t.classList.add('hidden'), 4000);
}

// ---------- rendering ----------
function stat(label, value, sub = '', percent = null) {
    return `<div class="card stat">
        <div class="label">${esc(label)}</div>
        <div class="value">${value}</div>
        ${sub ? `<div class="sub">${sub}</div>` : ''}
        ${percent !== null ? bar(percent) : ''}
    </div>`;
}

function renderResources(r) {
    if (!r) return '<div class="card muted">No data yet.</div>';
    const memUsed = usedPct(r.totalMemoryBytes, r.freeMemoryBytes);
    const swapUsed = usedPct(r.totalSwapBytes, r.freeSwapBytes);
    const heapPct = r.jvmHeapMaxBytes > 0 ? r.jvmHeapUsedBytes / r.jvmHeapMaxBytes * 100 : -1;
    const load = r.systemLoadAverage >= 0 ? ` · load ${r.systemLoadAverage.toFixed(2)}` : '';

    const disks = (r.disks || []).map(d =>
        stat(`Disk ${d.path}`, pct(usedPct(d.totalBytes, d.usableBytes)),
            `${bytes(d.totalBytes - d.usableBytes)} used of ${bytes(d.totalBytes)}`,
            usedPct(d.totalBytes, d.usableBytes))).join('');

    return `<div class="grid">
        ${stat('CPU (system)', pct(r.systemCpuLoadPercent), `${r.availableProcessors} cores${load}`, r.systemCpuLoadPercent)}
        ${stat('Memory', pct(memUsed), `${bytes(r.totalMemoryBytes - r.freeMemoryBytes)} used of ${bytes(r.totalMemoryBytes)}`, memUsed)}
        ${stat('Swap', pct(swapUsed), `${bytes(r.totalSwapBytes - r.freeSwapBytes)} used of ${bytes(r.totalSwapBytes)}`, swapUsed)}
        ${stat('JVM heap', pct(heapPct), `${bytes(r.jvmHeapUsedBytes)} of ${bytes(r.jvmHeapMaxBytes)} · process CPU ${pct(r.processCpuLoadPercent)}`, heapPct)}
        ${disks}
    </div>
    <h2>System</h2>
    <div class="card"><dl class="kv">
        <dt>Host</dt><dd>${esc(r.hostname)}</dd>
        <dt>OS</dt><dd>${esc(r.osName)} ${esc(r.osVersion)} (${esc(r.osArch)})</dd>
        <dt>Java</dt><dd>${esc(r.javaVersion)}</dd>
        <dt>Uptime</dt><dd>${duration(r.jvmUptimeMillis)}</dd>
        <dt>Sampled</dt><dd>${new Date(r.timestamp).toLocaleString()}</dd>
    </dl></div>`;
}

function renderMasterLink(info) {
    const l = info.masterLink;
    if (!l) return '';
    const ok = l.lastSuccess && !l.lastError;
    return `<div class="status ${ok ? 'ONLINE' : 'OFFLINE'}">${ok ? 'Connected to master' : 'Not connected to master'}</div>
        <dl class="kv" style="margin-top:8px">
            <dt>Master</dt><dd><a href="${esc(l.masterUrl)}" target="_blank">${esc(l.masterUrl)}</a></dd>
            <dt>Last heartbeat</dt><dd>${ago(l.lastSuccess)}</dd>
            ${l.lastError ? `<dt>Error</dt><dd class="error-text">${esc(l.lastError)}</dd>` : ''}
        </dl>`;
}

function renderConnect(info) {
    const url = info.advertisedUrl;
    const isMaster = info.role === 'MASTER';
    const masterForm = isMaster ? '' : `<div class="label">Master URL — paste the URL shown on the master's Overview page</div>
        <form id="master-url-form" class="copy-row">
            <input id="master-url-input" type="url" required placeholder="http://master-host:8080"
                   value="${esc(info.masterLink?.masterUrl)}">
            <button type="submit">Connect</button>
        </form>
        <hr>`;
    const label = isMaster ? 'Master URL — give this to workers to connect' : 'Worker URL — add this on the master to connect manually';
    const hint = isMaster
        ? `On a worker: set <code>nodegrid.master-url: ${esc(url)}</code> in <code>config/application-worker.yml</code>,
           run <code>start-worker --nodegrid.master-url=${esc(url)}</code>, or paste it in the worker's Settings → Master URL.`
        : `On the master: Workers → Add worker. Normally not needed, the worker registers itself with its master.`;
    const local = /\/\/(localhost|127\.|\[::1\])/.test(url)
        ? `<div class="hint error-text">This URL is local-only. Set “Advertised URL” in Settings so other machines can reach this node.</div>` : '';
    return `${masterForm}<div class="label">${label}</div>
        <div class="copy-row">
            <code id="connect-url">${esc(url)}</code>
            <button class="small" type="button" data-copy="${esc(url)}">Copy</button>
        </div>
        <div class="hint">${hint}</div>${local}`;
}

async function copyText(text) {
    try {
        await navigator.clipboard.writeText(text);
    } catch {
        // clipboard API needs a secure context (https/localhost); fall back for http://<ip>
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        ta.remove();
    }
}

function renderSummary(s) {
    const memUsed = usedPct(s.totalMemoryBytes, s.freeMemoryBytes);
    return [
        stat('Workers', `${s.online} / ${s.totalWorkers}`, `online · ${s.unreachable} unreachable · ${s.offline} offline`),
        stat('Cluster CPU (avg)', pct(s.averageCpuLoadPercent), `${s.totalCores} cores online`, s.averageCpuLoadPercent),
        stat('Cluster memory', pct(memUsed), `${bytes(s.totalMemoryBytes - s.freeMemoryBytes)} used of ${bytes(s.totalMemoryBytes)}`, memUsed),
        stat('Last poll cycle', s.lastPollCycle ? ago(s.lastPollCycle) : 'pending'),
    ].join('');
}

function renderWorkers(workers) {
    const body = $('#workers-body');
    if (!workers.length) {
        body.innerHTML = `<tr><td colspan="8" class="empty">No workers yet. Start a worker pointing to this master, or add one by URL.</td></tr>`;
        $('#worker-details').classList.add('hidden');
        return;
    }
    body.innerHTML = workers.map(w => {
        const r = w.resources;
        const memUsed = r ? usedPct(r.totalMemoryBytes, r.freeMemoryBytes) : -1;
        const disk = mainDisk(r);
        const diskUsed = disk ? usedPct(disk.totalBytes, disk.usableBytes) : -1;
        return `<tr data-id="${esc(w.id)}" class="${w.id === state.selectedWorker ? 'selected' : ''}">
            <td><strong>${esc(w.name)}</strong><br><span class="muted">${esc(w.url)}</span></td>
            <td><span class="status ${w.status}">${w.status}</span></td>
            <td>${pct(r?.systemCpuLoadPercent)}${bar(r?.systemCpuLoadPercent)}</td>
            <td>${pct(memUsed)}${bar(memUsed)}</td>
            <td>${pct(diskUsed)}${bar(diskUsed)}</td>
            <td>${r?.availableProcessors ?? '–'}</td>
            <td>${ago(w.lastSeen)}</td>
            <td>
                <button class="small" data-action="poll" data-id="${esc(w.id)}">Refresh</button>
                <button class="small danger" data-action="remove" data-id="${esc(w.id)}">Remove</button>
            </td>
        </tr>`;
    }).join('');

    const selected = workers.find(w => w.id === state.selectedWorker);
    const details = $('#worker-details');
    if (!selected) {
        details.classList.add('hidden');
        return;
    }
    details.classList.remove('hidden');
    details.innerHTML = `<h2>${esc(selected.name)} <span class="status ${selected.status}">${selected.status}</span></h2>
        <div class="card" style="margin-bottom:12px"><dl class="kv">
            <dt>ID</dt><dd>${esc(selected.id)}</dd>
            <dt>URL</dt><dd><a href="${esc(selected.url)}" target="_blank">${esc(selected.url)}</a></dd>
            <dt>Joined via</dt><dd>${esc(selected.source.toLowerCase())}</dd>
            <dt>Last poll</dt><dd>${ago(selected.lastPollAt)}</dd>
            <dt>Last heartbeat</dt><dd>${ago(selected.lastHeartbeat)}</dd>
            ${selected.lastError ? `<dt>Last error</dt><dd class="error-text">${esc(selected.lastError)} (${selected.consecutiveFailures} consecutive)</dd>` : ''}
        </dl></div>
        ${renderResources(selected.resources)}`;
}

// ---------- data loading ----------
async function refresh() {
    try {
        const info = await api('GET', '/api/node/info');
        state.info = info;
        $('#node-name').textContent = info.nodeName;
        $('#node-url').textContent = info.advertisedUrl;
        document.title = `NodeGrid · ${info.nodeName}`;

        if (state.tab === 'overview') {
            const connect = renderConnect(info);
            if (connect !== state.connectHtml) {
                $('#connect-info').innerHTML = state.connectHtml = connect;
            }
            const res = await api('GET', '/api/node/resources');
            $('#local-resources').innerHTML = renderResources(res);
            if (info.role === 'MASTER') {
                $('#cluster-summary').innerHTML = renderSummary(await api('GET', '/api/master/summary'));
            } else {
                $('#master-link').innerHTML = renderMasterLink(info);
            }
        } else if (state.tab === 'workers' && info.role === 'MASTER') {
            const [workers, summary] = await Promise.all([api('GET', '/api/master/workers'), api('GET', '/api/master/summary')]);
            renderWorkers(workers);
            $('#last-cycle').textContent = summary.lastPollCycle ? `Last poll cycle ${ago(summary.lastPollCycle)}` : 'Waiting for first poll…';
        }
    } catch (e) {
        toast(`Refresh failed: ${e.message}`, true);
    }
}

async function loadSettings() {
    const s = await api('GET', '/api/node/settings');
    const form = $('#settings-form');
    for (const [k, v] of Object.entries(s)) {
        if (form.elements[k]) form.elements[k].value = v ?? '';
    }
}

function applyRole(role) {
    $('#role-badge').textContent = role.toLowerCase();
    document.querySelectorAll('.master-only').forEach(el => el.classList.toggle('hidden', role !== 'MASTER'));
    document.querySelectorAll('.worker-only').forEach(el => el.classList.toggle('hidden', role !== 'WORKER'));
}

function switchTab(tab) {
    state.tab = tab;
    document.querySelectorAll('.tab').forEach(b => b.classList.toggle('active', b.dataset.tab === tab));
    document.querySelectorAll('.tab-panel').forEach(p => p.classList.toggle('hidden', p.id !== `tab-${tab}`));
    if (tab === 'settings') {
        loadSettings().catch(e => toast(e.message, true));
    } else {
        refresh();
    }
}

// ---------- events ----------
document.querySelectorAll('.tab').forEach(b => b.addEventListener('click', () => switchTab(b.dataset.tab)));

$('#connect-info').addEventListener('click', async (e) => {
    const btn = e.target.closest('button[data-copy]');
    if (!btn) return;
    await copyText(btn.dataset.copy);
    toast(`Copied ${btn.dataset.copy}`);
});

$('#connect-info').addEventListener('submit', async (e) => {
    if (e.target.id !== 'master-url-form') return;
    e.preventDefault();
    const btn = e.target.querySelector('button');
    btn.disabled = true;
    try {
        const s = await api('GET', '/api/node/settings');
        await api('PUT', '/api/node/settings', { ...s, masterUrl: $('#master-url-input').value.trim() });
        toast('Master URL saved, connecting…');
        // saving triggers an immediate heartbeat; give it a moment before showing the result
        setTimeout(refresh, 1500);
    } catch (err) {
        toast(err.message, true);
    } finally {
        btn.disabled = false;
    }
});

$('#workers-body').addEventListener('click', async (e) => {
    const btn = e.target.closest('button[data-action]');
    if (btn) {
        e.stopPropagation();
        const id = btn.dataset.id;
        btn.disabled = true;
        try {
            if (btn.dataset.action === 'poll') {
                await api('POST', `/api/master/workers/${encodeURIComponent(id)}/poll`);
            } else if (btn.dataset.action === 'remove') {
                if (!confirm('Remove this worker? A worker that registers itself will re-appear on its next heartbeat.')) return;
                await api('DELETE', `/api/master/workers/${encodeURIComponent(id)}`);
                if (state.selectedWorker === id) state.selectedWorker = null;
            }
            await refresh();
        } catch (err) {
            toast(err.message, true);
        } finally {
            btn.disabled = false;
        }
        return;
    }
    const row = e.target.closest('tr[data-id]');
    if (row) {
        state.selectedWorker = state.selectedWorker === row.dataset.id ? null : row.dataset.id;
        refresh();
    }
});

$('#add-worker-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const input = $('#add-worker-url');
    const btn = e.target.querySelector('button');
    btn.disabled = true;
    try {
        const w = await api('POST', '/api/master/workers', { url: input.value });
        toast(`Added worker ${w.name}`);
        input.value = '';
        await refresh();
    } catch (err) {
        toast(err.message, true);
    } finally {
        btn.disabled = false;
    }
});

$('#settings-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target.elements;
    const num = (n) => parseInt(f[n].value, 10);
    try {
        await api('PUT', '/api/node/settings', {
            nodeName: f.nodeName.value,
            advertisedUrl: f.advertisedUrl.value,
            masterUrl: f.masterUrl.value,
            pollIntervalSeconds: num('pollIntervalSeconds'),
            heartbeatIntervalSeconds: num('heartbeatIntervalSeconds'),
            requestTimeoutSeconds: num('requestTimeoutSeconds'),
            offlineAfterFailures: num('offlineAfterFailures'),
        });
        toast('Settings saved');
        await loadSettings();
        refresh();
    } catch (err) {
        toast(err.message, true);
    }
});

$('#reset-settings').addEventListener('click', async () => {
    if (!confirm('Reset all settings to the values from application.yml?')) return;
    try {
        await api('DELETE', '/api/node/settings');
        await loadSettings();
        toast('Settings reset to defaults');
    } catch (err) {
        toast(err.message, true);
    }
});

// ---------- boot ----------
(async function init() {
    try {
        const info = await api('GET', '/api/node/info');
        state.info = info;
        applyRole(info.role);
    } catch (e) {
        toast(`Cannot reach node API: ${e.message}`, true);
    }
    switchTab('overview');
    state.timer = setInterval(() => { if (state.tab !== 'settings') refresh(); }, REFRESH_MS);
})();
