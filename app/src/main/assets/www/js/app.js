// ==================== Sakura Player - Core App ====================
const $ = (s, p) => (p || document).querySelector(s);
const $$ = (s, p) => Array.from((p || document).querySelectorAll(s));

// State
let currentTab = 'home';
let currentView = 'home';
let callbackId = 0;
const callbacks = {};
let domain = '';
let downloadPollTimer = null;

// Navigation stack: tracks overlay pages only (search, detail, settings)
let navStack = [{type:'tab', name:'home'}];
// Per-tab overlay stacks: each tab remembers its own open overlays
// Structure: { home: ['search'], discover: ['search','detail'], ... }
const tabOverlayStack = { home: [], discover: [], download: [], me: [] };
// Per-tab data cache: preserve content across tab switches
const tabCache = {
    home: { loaded: false, homeTab: 'local', localHTML: '', followHTML: '', scrollTop: 0 },
    discover: { loaded: false, items: [], cat: 'recommend', scrollTop: 0 },
    download: { loaded: false, tasks: [], scrollTop: 0 },
    me: { loaded: true, scrollTop: 0 }
};
// Double-back exit tracking
let lastBackTime = 0;
window._shouldExit = false;

// ==================== JSBridge Helpers ====================

function callNative(fn, ...args) {
    return new Promise((resolve, reject) => {
        const cid = 'cb_' + (++callbackId);
        callbacks[cid] = { resolve, reject };
        const allArgs = [...args, cid];
        if (window.Sakura && window.Sakura[fn]) {
            window.Sakura[fn](...allArgs);
        } else {
            reject(new Error('Bridge not ready: '+fn));
        }
    });
}

// These are called by Kotlin via evaluateJavascript
window.resolveCallback = function(cid, err, data) {
    const cb = callbacks[cid];
    if (!cb) return;
    delete callbacks[cid];
    if (err) cb.reject(new Error(err));
    else cb.resolve(typeof data === 'string' ? JSON.parse(data) : data);
};

// Kotlin calls this pattern: callbackName(null, json) or callbackName('error', null)
// So we need to wrap native calls that use the legacy pattern
function callNativeLegacy(fn, ...args) {
    return new Promise((resolve, reject) => {
        const cid = 'cb_' + (++callbackId);

        // Override the global callback handler for this specific call
        const legacyHandler = (err, data) => {
            delete window['_legacy_' + cid];
            if (err) reject(new Error(err));
            else resolve(data);
        };
        window['_legacy_' + cid] = legacyHandler;

        if (window.Sakura && window.Sakura[fn]) {
            window.Sakura[fn](...args, '_legacy_' + cid);
        } else {
            reject(new Error('Bridge not ready: '+fn));
        }
    });
}

// ==================== Tab Navigation ====================

$$('#tab-bar .tab-item').forEach(item => {
    item.addEventListener('click', () => {
        const tab = item.dataset.tab;
        switchTab(tab);
    });
});

function switchTab(tab) {
    // Save current tab state
    const cv = $('#view-' + currentTab);
    if (cv) tabCache[currentTab].scrollTop = cv.scrollTop || 0;

    currentTab = tab;

    // Update tab bar indicator
    $$('#tab-bar .tab-item').forEach(t => t.classList.toggle('active', t.dataset.tab === tab));

    // Hide ALL views
    $$('.view.overlay').forEach(v => v.classList.remove('active'));
    $$('.view:not(.overlay)').forEach(v => v.classList.remove('active'));

    // Show target tab's base view
    const viewEl = $('#view-' + tab);
    if (viewEl) {
        viewEl.classList.add('active');
        viewEl.scrollTop = tabCache[tab].scrollTop || 0;
    }

    // RESTORE any overlay that was open on this tab
    const stack = tabOverlayStack[tab];
    if (stack.length > 0) {
        const topOverlay = stack[stack.length - 1];
        const overlayEl = $('#view-' + topOverlay);
        if (overlayEl) overlayEl.classList.add('active');
    }

    // Nav stack: keep all entries. tabOverlayStack handles per-tab visibility.
    // Only add this tab if not already the last tab entry.
    const lastEntry = navStack.length > 0 ? navStack[navStack.length-1] : null;
    if (!lastEntry || lastEntry.type !== 'tab' || lastEntry.name !== tab) {
        navStack.push({type:'tab', name:tab});
    }

    // Load data once, cache forever
    if (!tabCache[tab].loaded) {
        tabCache[tab].loaded = true;
        if (tab === 'home') loadHome();
        else if (tab === 'discover') loadDiscover();
        else if (tab === 'download') loadDownload();
    }
}

// ==================== View Overlays ====================

function openOverlay(viewName) {
    currentView = viewName;

    // Show this overlay on top
    $$('.view.overlay').forEach(v => v.classList.remove('active'));
    const el = $('#view-' + viewName);
    if (el) el.classList.add('active');

    // Track this overlay under the current tab
    tabOverlayStack[currentTab].push(viewName);
    navStack.push({type:'overlay', name:viewName, tab: currentTab});
    if (navStack.length > 20) navStack = navStack.slice(-15);
}

function closeOverlay() {
    // Pop the current tab's overlay stack
    const stack = tabOverlayStack[currentTab];
    if (stack.length > 0) stack.pop();

    $$('.view.overlay').forEach(v => v.classList.remove('active'));
    while (navStack.length > 0 && navStack[navStack.length-1].type === 'overlay') {
        navStack.pop();
    }

    // Show the previous overlay in this tab's stack, or the tab itself
    if (stack.length > 0) {
        const prevOverlay = stack[stack.length - 1];
        const el = $('#view-' + prevOverlay);
        if (el) el.classList.add('active');
    } else {
        // Restore underlying tab
        const lastTab = navStack.length > 0 ? navStack[navStack.length-1].name : 'home';
        currentTab = lastTab;
        $$('#tab-bar .tab-item').forEach(t => t.classList.toggle('active', t.dataset.tab === lastTab));
        const viewEl = $('#view-' + lastTab);
        if (viewEl) {
            viewEl.classList.add('active');
            viewEl.scrollTop = tabCache[lastTab].scrollTop || 0;
        }
    }
}

// ==================== Toast ====================

let toastTimer;
function showToast(msg, duration = 2000) {
    const t = $('#toast');
    t.innerHTML = msg;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), duration);
}

// ==================== Card Rendering ====================

function renderCard(item) {
    const badge = item.isLocal ? '<span class="card-badge card-local-badge">本地</span>' : '';
    const cover = item.coverUrl
        ? `<img src="${item.coverUrl}" alt="" loading="lazy" onerror="this.style.display='none';this.insertAdjacentHTML('afterend','<div class=card-thumb-placeholder>🎬</div>')">`
        : '<div class="card-thumb-placeholder">🎬</div>';

    return `<div class="card" onclick="openDetail(${item.videoId},${!!item.isLocal},'${(item.localPath||'').replace(/'/g,"\\'")}')">
      <div class="card-img-wrap">${badge}${cover}</div>
      <div class="card-info">
        <div class="card-title">${escHtml(item.title)}</div>
        <div class="card-meta">${item.episodeInfo || ''}</div>
        ${item.followProgress ? `<div class="follow-progress"><div class="follow-progress-fill" style="width:${item.followProgress}%"></div></div>` : ''}
        ${item.hasUpdate ? '<div class="follow-update-dot"></div>' : ''}
      </div>
    </div>`;
}

function escHtml(s) {
    const d = document.createElement('div');
    d.textContent = s;
    return d.innerHTML;
}

// ==================== Domain Ready ====================

// Callback for syncDownloadRecords bridge call
window._cb_sync = function(err, count) {
    if (!err && count > 0) console.log('下载记录已同步: ' + count + ' 条');
};

window.onDomainReady = function(d) {
    domain = d;
    $('#domain-display').textContent = '当前站点: ' + d;
    $('#setting-domain').textContent = d;
    if (currentTab === 'discover') loadDiscover();
};

window.onDomainRefresh = function(d) {
    domain = d;
    $('#domain-display').textContent = '当前站点: ' + d;
    $('#setting-domain').textContent = d;
    showToast('域名已更新: ' + new URL(d).hostname);
};

window.showError = function(msg) {
    showToast(msg, 4000);
};

// ==================== Follow Update Notification ====================

window.onFollowUpdate = function(videoId, newEps) {
    showToast('你追的番更新了 ' + newEps + ' 集！');
    if (currentTab === 'home') loadFollowList();
};

// ==================== Init ====================

document.addEventListener('DOMContentLoaded', () => {
    if (typeof initHomeTabs === 'function') initHomeTabs();
    loadHome();
    loadDiscover();
    loadDownloadsPath();
    pollDownloads();
});

function loadDownloadsPath() {
    try {
        const path = window.Sakura ? window.Sakura.getDownloadsPath() : '/storage/emulated/0/SakuraAnime';
        $('#setting-path').textContent = path;
    } catch(e) {}
}

// ==================== Download Polling ====================

let pollActive = false;
function pollDownloads() {
    if (pollActive) return;
    pollActive = true;
    const poll = async () => {
        if (currentView !== 'download' && currentTab !== 'download') {
            downloadPollTimer = setTimeout(poll, 3000);
            return;
        }
        try {
            const data = await callNativeLegacy('getDownloadStatus');
            tabCache.download.tasks = Array.isArray(data) ? data : [];
            renderDownloadList(tabCache.download.tasks);
        } catch(e) {}
        const interval = ($$('.dl-item').length > 0) ? 500 : 3000;
        downloadPollTimer = setTimeout(poll, interval);
    };
    poll();
}

// ==================== Navigation Helpers ====================

function goSearch() { openOverlay('search'); setTimeout(() => $('#search-input')?.focus(), 300); }
function closeSearch() { closeOverlay(); }
function closeDetail() { closeOverlay(); }
function goSettings() { openOverlay('settings'); loadSettings(); }
function closeSettings() { closeOverlay(); }

function showDevLog() { openOverlay('devlog'); }
function closeDevLog() { closeOverlay(); }

function showAbout() {
    showToast('樱花动漫播放器 v1.0.0<br>作者: 奶球<br>ALL RIGHTS RESERVED.<br>出Ave Mujica7thS席', 3000);
}

// ==================== Discover Page ====================

let discoverCat = 'recommend';
$$('.dcat').forEach(d => {
    d.addEventListener('click', () => {
        discoverCat = d.dataset.cat;
        $$('.dcat').forEach(x => x.classList.toggle('active', x.dataset.cat === discoverCat));
        loadDiscover();
    });
});

async function loadDiscover() {
    const cache = tabCache.discover;
    // Re-render from cache instantly
    if (cache.items.length > 0) {
        $('#discover-grid').innerHTML = cache.items.map(renderCard).join('');
    } else {
        $('#discover-loading').style.display = '';
    }
    // Then refresh from network
    try {
        const data = await callNativeLegacy('getDiscover', 1);
        cache.items = (Array.isArray(data) ? data : []).map(r => ({...r, isLocal: false}));
        $('#discover-grid').innerHTML = cache.items.map(renderCard).join('');
    } catch(e) {
        if (cache.items.length === 0) handleApiError(e);
    }
    $('#discover-loading').style.display = 'none';
}

// ==================== Download Page ====================

async function loadDownload() {
    // Re-render from cache instantly
    if (tabCache.download.tasks.length > 0) {
        renderDownloadList(tabCache.download.tasks);
    }
    // Then refresh from native
    try {
        const data = await callNativeLegacy('getDownloadStatus');
        tabCache.download.tasks = Array.isArray(data) ? data : [];
        renderDownloadList(tabCache.download.tasks);
    } catch(e) {}
}

function renderDownloadList(tasks) {
    const el = $('#dl-list');
    if (!el) return;
    if (!tasks.length) {
        el.innerHTML = '';
        $('#dl-empty').style.display = '';
        return;
    }
    $('#dl-empty').style.display = 'none';
    el.innerHTML = tasks.map(t => {
        const statusClass = t.status || 'queued';
        const statusText = { queued: '排队中', downloading: '下载中', paused: '已暂停', completed: '完成', failed: '失败' }[t.status] || t.status;
        return `<div class="dl-item">
          <div class="dl-header">
            <span class="dl-title">${escHtml(t.title)} 第${t.epIndex}集</span>
            <span class="dl-status ${statusClass}">${statusText}</span>
          </div>
          <div class="dl-progress-wrap"><div class="dl-progress-fill" style="width:${t.progress||0}%"></div></div>
          <div class="dl-info"><span>${t.progress||0}%</span><span>${t.speed||''} ${t.eta||''}</span></div>
          ${t.error ? `<div style="color:#FF4D4F;font-size:11px;margin-top:4px">${escHtml(t.error)}</div>` : ''}
          ${t.status !== 'completed' ? `<div class="dl-actions">
            ${t.status === 'failed' ? `<button onclick="window.Sakura.retryDownload('${t.id}')">重新下载</button>` : (t.status === 'paused' ? `<button onclick="window.Sakura.resumeDownload('${t.id}')">恢复</button>` : `<button onclick="window.Sakura.pauseDownload('${t.id}')">暂停</button>`)}
            <button onclick="window.Sakura.cancelDownload('${t.id}')">取消</button>
          </div>` : ''}
        </div>`;
    }).join('');
}

function resumeAllDownloads() {
    var tasks = tabCache.download.tasks || [];
    var count = 0;
    tasks.forEach(function(t) {
        if (t.status === 'paused') {
            window.Sakura.resumeDownload(t.id);
            count++;
        }
    });
    showToast('已恢复 ' + count + ' 个下载');
}

function pauseAllDownloads() {
    var tasks = tabCache.download.tasks || [];
    var count = 0;
    tasks.forEach(function(t) {
        if (t.status === 'downloading' || t.status === 'queued') {
            window.Sakura.pauseDownload(t.id);
            count++;
        }
    });
    showToast('已暂停 ' + count + ' 个下载');
}

function cancelAllDownloads() {
    if (!confirm('确定取消全部下载？')) return;
    var tasks = tabCache.download.tasks || [];
    tasks.forEach(function(t) {
        window.Sakura.cancelDownload(t.id);
    });
    showToast('已取消全部下载');
    setTimeout(function() { loadDownload(); }, 500);
}

// ==================== Error Handler ====================

function handleApiError(err) {
    const msg = err.message || String(err);
    if (msg.includes('域名') || msg.includes('domain') || msg.includes('访问') || msg.includes('暂时')) {
        showToast('站点暂时无法访问，正在切换备用域名...', 3000);
        if (window.Sakura) window.Sakura.refreshDomain();
    } else {
        showToast(msg, 2500);
    }
}

// ==================== Back Navigation ====================
// Called by Kotlin onBackPressed

window.handleBackPress = function() {
    // 1. Selection mode active → exit selection mode
    if (window.localSelectMode) {
        window.exitSelectMode();
        window._shouldExit = false;
        return;
    }

    // 2. Current tab has an open overlay → close it first
    if (tabOverlayStack[currentTab].length > 0) {
        // Remove from navStack too
        for (let i = navStack.length - 1; i >= 0; i--) {
            if (navStack[i].type === 'overlay' && navStack[i].tab === currentTab) {
                navStack.splice(i, 1);
                break;
            }
        }
        closeOverlay();
        window._shouldExit = false;
        return;
    }

    // 3. Local file browser: navigate back up the folder tree
    if (window.localHistory && window.localHistory.length > 1) {
        window.localHistory.pop();
        window.browseInto(window.localHistory[window.localHistory.length - 1]);
        window._shouldExit = false;
        return;
    }

    // 4. Non-home tab → go to home
    if (currentTab !== 'home') {
        switchTab('home');
        window._shouldExit = false;
        return;
    }

    // 5. At home tab — double-back to exit
    const now = Date.now();
    if (now - lastBackTime < 2000) {
        window._shouldExit = true;
    } else {
        lastBackTime = now;
        showToast('再按一次退出', 1500);
        window._shouldExit = false;
    }
};

