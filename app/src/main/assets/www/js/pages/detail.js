// ==================== Detail Page (B站風格) ====================

window.currentDetail = null;
window.playerState = { playing: false, locked: false, dragP: false, bri: 0, currentEp: 0 };
let playerTouch = { x:0, y:0, lx:0, ly:0, vol:0, bri:0, time:0, seekT:0, lastTap:0 };
let playerGest = { type: null, lpFired: false, lpTimer: null, tapTimer: null };
// ExoPlayer polling timer for local file playback
let localPlayerPollTimer = null;

async function openDetail(videoId, isLocal, localPath) {
    openOverlay('detail');
    resetPlayer();
    $('#detail-title').textContent = '加载中...';
    $('#d-title').textContent = '';
    $('#d-tags').innerHTML = '';
    $('#d-desc').textContent = '';
    $('#episode-grid').innerHTML = '';
    $('#player-cover-img').src = '';

    try {
        const data = await callNativeLegacy('getDetail', videoId, isLocal, localPath || '');
        window.currentDetail = data;
        renderDetail(data);
        checkFollowStatus(videoId);
    } catch(e) {
        handleApiError(e);
    }
}

function renderDetail(detail) {
    $('#detail-title').textContent = detail.title;
    $('#d-title').textContent = detail.title;
    $('#d-desc').textContent = detail.description || '';

    if (detail.tags && detail.tags.length) {
        $('#d-tags').innerHTML = detail.tags.map(t => `<span class="tag">${escHtml(t)}</span>`).join('');
    } else {
        $('#d-tags').innerHTML = '';
    }

    // Cover image
    if (detail.coverUrl) {
        $('#player-cover-img').src = detail.coverUrl;
    }

    // Toggle buttons: local → re-download, online → follow + batch download
    const actionsEl = $('#detail-actions');
    if (actionsEl) actionsEl.style.display = '';
    const btnFollow = $('#btn-follow-detail');
    if (btnFollow) btnFollow.style.display = detail.isLocal ? 'none' : '';
    const btnBatchDl = $('#btn-dl-all');
    if (btnBatchDl) btnBatchDl.style.display = detail.isLocal ? 'none' : '';
    const btnReDl = $('#btn-re-dl');
    if (btnReDl) btnReDl.style.display = detail.isLocal ? '' : 'none';

    // Episodes
    if (detail.episodes && detail.episodes.length) {
        $('#episode-grid').innerHTML = detail.episodes.map(ep => `
          <div class="ep-btn ${window.playerState.currentEp === ep.index ? 'playing' : ''}" data-ep="${ep.index}">
            ${ep.name}
          </div>
        `).join('');

        $$('#episode-grid .ep-btn').forEach(btn => {
            // Click to play
            btn.addEventListener('click', () => {
                const ep = parseInt(btn.dataset.ep);
                playEpisode(ep);
            });
            // Long press to download
            let pressTimer;
            btn.addEventListener('touchstart', () => {
                pressTimer = setTimeout(() => {
                    const ep = parseInt(btn.dataset.ep);
                    downloadEpisode(window.currentDetail.videoId, window.currentDetail.title, ep, '第' + ep + '集');
                }, 600);
            });
            btn.addEventListener('touchend', () => clearTimeout(pressTimer));
            btn.addEventListener('touchmove', () => clearTimeout(pressTimer));
        });
    }
}

function resetPlayer() {
    // Stop local player polling
    if (localPlayerPollTimer) {
        clearInterval(localPlayerPollTimer);
        localPlayerPollTimer = null;
    }
    // Release ExoPlayer if active
    if (window.LocalPlayer) {
        try { window.LocalPlayer.release(); } catch(e) {}
    }
    // Hide native SakuraPlayer
    try { window.Sakura.setSakuraPlayerVisible(false); } catch(e) {}
    // Clear global document-level pointer handlers to avoid stale handler conflicts
    document.onpointermove = null;
    document.onpointerup = null;
    var track = $('#p-track');
    if (track) track.onpointerdown = null;

    const v = $('#detail-video');
    v.style.display = 'none';
    v.pause();
    v.removeAttribute('src');
    v.load();
    $('#player-cover').style.display = '';
    $('#player-ctrls').style.display = '';
    $('#player-loading').style.display = 'none';
    window.playerState.playing = false;
    window.playerState.currentEp = 0;
    $('#p-pp').textContent = '\u25B6';
    $('#p-fill').style.width = '0%';
    $('#p-dot').style.left = '0%';
    $('#p-buf').style.width = '0%';
    $('#p-tcur').textContent = '00:00';
    $('#p-ttot').textContent = '00:00';
}

function playEpisode(epIndex) {
    if (!window.currentDetail) return;
    window.playerState.currentEp = epIndex;

    // Highlight active episode
    $$('#episode-grid .ep-btn').forEach(b => b.classList.toggle('playing', parseInt(b.dataset.ep) === epIndex));

    // Reset previous local player
    if (localPlayerPollTimer) {
        clearInterval(localPlayerPollTimer);
        localPlayerPollTimer = null;
    }
    if (window.LocalPlayer) {
        try { window.LocalPlayer.release(); } catch(e) {}
    }

    const v = $('#detail-video');
    $('#player-cover').style.display = 'none';
    $('#player-loading').style.display = '';

    if (window.currentDetail.isLocal) {
        // Local playback: use SakuraPlayerView (same B站 UI as online)
        v.style.display = 'none';
        $('#player-ctrls').style.display = 'none';

        const ep = window.currentDetail.episodes?.find(e => e.index === epIndex);
        if (ep && ep.path) {
            startLocalPlayer(ep.path);
        } else {
            showToast('找不到本地文件');
            $('#player-loading').style.display = 'none';
        }
    } else {
        // Online: hide WebView controls — native SakuraPlayer handles UI
        v.style.display = 'none';
        $('#player-ctrls').style.display = 'none';
        // Online streaming: use native SakuraPlayer (ExoPlayer + B站 gesture controls)
        var playerArea = $('#player-area');
        var rect = playerArea.getBoundingClientRect();
        var dpr = window.devicePixelRatio || 1;
        var xPx = Math.round(rect.left * dpr);
        var yPx = Math.round(rect.top * dpr);
        var wPx = Math.round(rect.width * dpr);
        var hPx = Math.round(rect.height * dpr);

        var episodesJson = buildEpisodesJson();
        window.Sakura.playOnlineNative(
            window.currentDetail.videoId,
            window.currentDetail.title,
            epIndex,
            episodesJson,
            xPx, yPx, wPx, hPx
        );
        // Native player will handle loading UI via onStateChanged callback
    }
}

function buildEpisodesJson() {
    if (!window.currentDetail || !window.currentDetail.episodes) return '[]';
    return JSON.stringify(window.currentDetail.episodes.map(function(ep) {
        return {
            index: ep.index,
            name: ep.name,
            path: ep.path || '',
            videoId: window.currentDetail.videoId || 0,
            isLocal: window.currentDetail.isLocal || false
        };
    }));
}

// ==================== SakuraPlayer Unified Playback (local + online) ====================

function startLocalPlayer(filePath) {
    // Use SakuraPlayerView for local files too — same B站 gestures and controls
    var playerArea = $('#player-area');
    var rect = playerArea.getBoundingClientRect();
    var dpr = window.devicePixelRatio || 1;
    var xPx = Math.round(rect.left * dpr);
    var yPx = Math.round(rect.top * dpr);
    var wPx = Math.round(rect.width * dpr);
    var hPx = Math.round(rect.height * dpr);

    var episodesJson = buildEpisodesJson();
    window.Sakura.playLocalNative(filePath, episodesJson, xPx, yPx, wPx, hPx);
}

// Old ExoPlayer direct bridge (kept for backward compat, not used for new player)
function startLocalPlayerLegacy(filePath) {
    // Get #player-area position in physical pixels
    const playerArea = $('#player-area');
    const rect = playerArea.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    // Leave ~72dp at the bottom for WebView controls (progress bar at bottom:40px + track ~24px + button row ~26px + buffer)
    const controlBarReserve = Math.round(72 * dpr);
    const xPx = Math.round(rect.left * dpr);
    const yPx = Math.round(rect.top * dpr);
    const wPx = Math.round(rect.width * dpr);
    const hPx = Math.round(rect.height * dpr) - controlBarReserve;

    // Hide WebView video and cover
    const v = $('#detail-video');
    v.style.display = 'none';
    v.pause();
    v.removeAttribute('src');
    v.load();
    $('#player-cover').style.display = 'none';

    // Start ExoPlayer via native bridge
    window.LocalPlayer.play(filePath, xPx, yPx, wPx, hPx);
    // bindLocalPlayerControls will be called by native after player is ready
}

// Called from native when ExoPlayer is ready (via evalJs in LocalPlayer.play)
function bindLocalPlayerControls() {
    const fm = s => s && isFinite(s) ? String(Math.floor(s/60)).padStart(2,'0')+':'+String(Math.floor(s%60)).padStart(2,'0') : '00:00';

    // Clear any existing poll
    if (localPlayerPollTimer) clearInterval(localPlayerPollTimer);

    // Poll ExoPlayer state every 400ms
    localPlayerPollTimer = setInterval(() => {
        try {
            if (!window.LocalPlayer) { clearInterval(localPlayerPollTimer); return; }
            const stateStr = window.LocalPlayer.getState();
            const state = JSON.parse(stateStr);
            if (!state || !state.duration) return;

            // Update progress bar
            if (!window.playerState.dragP && state.duration > 0) {
                const pct = (state.position / state.duration) * 100;
                $('#p-fill').style.width = pct + '%';
                $('#p-dot').style.left = pct + '%';
            }
            $('#p-tcur').textContent = fm(state.position / 1000);
            $('#p-ttot').textContent = fm(state.duration / 1000);

            // Update play/pause icon
            if (state.playbackState === 4) { // STATE_ENDED
                $('#p-pp').textContent = '\u{1F504}';
                window.playerState.playing = false;
            } else if (state.playing) {
                $('#p-pp').textContent = '\u23F8';
                window.playerState.playing = true;
            } else {
                $('#p-pp').textContent = '\u25B6';
                window.playerState.playing = false;
            }
        } catch(e) {}
    }, 400);

    // Play/Pause button
    $('#p-pp').onclick = () => {
        if (window.LocalPlayer) window.LocalPlayer.toggle();
    };

    // Fullscreen button - open fullscreen PlayerActivity
    $('#p-fs').onclick = () => {
        if (window.currentDetail?.isLocal) {
            const ep = window.currentDetail.episodes?.find(e => e.index === window.playerState.currentEp);
            if (ep && ep.path) {
                // Get current position before launching fullscreen
                var currentPos = 0;
                try {
                    var st = JSON.parse(window.LocalPlayer.getState());
                    currentPos = Math.round(st.position || 0);
                } catch(e) {}
                // Pause local player
                if (window.LocalPlayer) window.LocalPlayer.pause();
                callNativeLegacy('getLocalVideoUrl', ep.path).then(function(contentUrl) {
                    window.Sakura.playLocalFromUrl(contentUrl, window.currentDetail.title, window.playerState.currentEp, currentPos);
                });
            }
        } else {
            callNativeLegacy('openFullscreen', window.currentDetail?.videoId || 0,
                window.currentDetail?.title || '', window.playerState.currentEp).catch(() => {});
        }
    };

    // Lock button
    $('#p-lock').onclick = () => toggleLock();

    // Progress bar drag for ExoPlayer
    const track = $('#p-track');
    const pointerDownExo = function(e) {
        if (window.playerState.locked) return;
        window.playerState.dragP = true;
        track.setPointerCapture(e.pointerId);
        $('#p-dot').style.transform = 'translate(-50%,-50%) scale(1.4)';
        setProgExo(e.clientX);
        const stateStr = window.LocalPlayer ? window.LocalPlayer.getState() : '{}';
        try {
            const state = JSON.parse(stateStr);
            const pv = $('#p-seek-pv');
            if (pv && state.duration > 0) {
                pv.querySelector('.cur').textContent = fm(state.position / 1000);
                pv.querySelector('.total').textContent = fm(state.duration / 1000);
                pv.classList.add('on');
            }
        } catch(e) {}
    };
    const pointerMoveExo = function(e) {
        if (!window.playerState.dragP || window.playerState.locked) return;
        setProgExo(e.clientX);
        const stateStr = window.LocalPlayer ? window.LocalPlayer.getState() : '{}';
        try {
            const state = JSON.parse(stateStr);
            if (state.duration > 0) {
                const pv = $('#p-seek-pv');
                if (pv) pv.querySelector('.cur').textContent = fm(state.position / 1000);
            }
        } catch(e) {}
    };
    const pointerUpExo = function() {
        if (!window.playerState.dragP) return;
        window.playerState.dragP = false;
        $('#p-dot').style.transform = 'translate(-50%,-50%)';
        const pv = $('#p-seek-pv');
        if (pv) pv.classList.remove('on');
        const stateStr = window.LocalPlayer ? window.LocalPlayer.getState() : '{}';
        try {
            const state = JSON.parse(stateStr);
            if (state.duration > 0) {
                const pc = (state.position / state.duration) * 100;
                $('#p-fill').style.width = pc + '%';
                $('#p-dot').style.left = pc + '%';
            }
        } catch(e) {}
    };

    // Remove old listeners and add new ones
    track.onpointerdown = pointerDownExo;
    document.onpointermove = pointerMoveExo;
    document.onpointerup = pointerUpExo;

    function setProgExo(clientX) {
        const inner = $('#p-inner');
        if (!inner) return;
        const r = inner.getBoundingClientRect();
        const p = Math.max(0, Math.min(1, (clientX - r.left) / r.width));
        const stateStr = window.LocalPlayer ? window.LocalPlayer.getState() : '{}';
        try {
            const state = JSON.parse(stateStr);
            if (state.duration > 0) {
                const seekMs = Math.round(p * state.duration);
                window.LocalPlayer.seek(seekMs);
            }
        } catch(e) {}
        $('#p-fill').style.width = (p*100) + '%';
        $('#p-dot').style.left = Math.round(clientX - r.left) + 'px';
    }
}

// Called from native when ExoPlayer is released (detail closed)
window.onLocalPlayerReleased = function() {
    if (localPlayerPollTimer) {
        clearInterval(localPlayerPollTimer);
        localPlayerPollTimer = null;
    }
};

// ==================== Retained player helpers ====================

function toggleLock() {
    window.playerState.locked = !window.playerState.locked;
    $('#p-lock').textContent = window.playerState.locked ? '\u{1F512}' : '\u{1F513}';
}

function showPHud(type, val) {
    const hud = $('#p-' + type);
    hud.querySelector('.p-hud-fill').style.height = val + '%';
    hud.querySelector('.p-hud-label').textContent = val + '%';
    hud.classList.add('on');
    clearTimeout(hud._t);
    hud._t = setTimeout(() => hud.classList.remove('on'), 800);
}

function showPTip(msg) {
    const tip = $('#p-tip-speed');
    tip.textContent = msg;
    tip.classList.add('on');
}

// ==================== Actions ====================

function downloadEpisode(videoId, title, epIndex, epName) {
    var coverUrl = (window.currentDetail && window.currentDetail.coverUrl) ? window.currentDetail.coverUrl : '';
    window.Sakura.addDownload(videoId, title, epIndex, epName, '', coverUrl);
    showToast('已加入下载队列: ' + title + ' 第' + epIndex + '集');
}

async function batchDownload() {
    if (!window.currentDetail || !window.currentDetail.episodes) return;
    var videoId = window.currentDetail.videoId;
    var coverUrl = window.currentDetail.coverUrl || '';

    // Filter out already-downloaded episodes via DB records
    var downloadedSet = new Set();
    try {
        var dbEps = await callNativeLegacy('getDownloadedEps', videoId);
        if (Array.isArray(dbEps)) dbEps.forEach(function(e) { downloadedSet.add(e); });
    } catch(e) {}

    var eps = window.currentDetail.episodes
        .filter(function(ep) { return !downloadedSet.has(ep.index); })
        .map(function(ep) { return {
            videoId: videoId,
            title: window.currentDetail.title,
            epIndex: ep.index,
            epName: ep.name
        }; });

    if (eps.length === 0) {
        showToast('全部已下载，无需重复下载');
        return;
    }

    var skipped = window.currentDetail.episodes.length - eps.length;
    window.Sakura.addBatchDownload(JSON.stringify(eps), coverUrl);
    var msg = '已加入下载队列: ' + eps.length + ' 集';
    if (skipped > 0) msg += '（已跳过 ' + skipped + ' 集已下载）';
    showToast(msg);
}

async function toggleFollow() {
    if (!window.currentDetail) return;
    const vid = window.currentDetail.videoId;
    try {
        const follows = await callNativeLegacy('getFollows');
        const existing = follows.find(f => f.videoId === vid);
        if (existing) {
            window.Sakura.removeFollow(vid);
            showToast('已取消追番');
            $('#btn-follow-detail').textContent = '\u2B50 追番';
        } else {
            window.Sakura.addFollow(vid, window.currentDetail.title, window.currentDetail.coverUrl, window.currentDetail.totalEps || window.currentDetail.episodes?.length || 0);
            showToast('已加入追番列表');
            $('#btn-follow-detail').textContent = '\u2B50 已追番';
        }
    } catch(e) { handleApiError(e); }
}

async function checkFollowStatus(videoId) {
    try {
        const follows = await callNativeLegacy('getFollows');
        if (follows.find(f => f.videoId === videoId)) $('#btn-follow-detail').textContent = '\u2B50 已追番';
    } catch(e) {}
}

// ==================== Native SakuraPlayer callbacks ====================

/** Called by native when SakuraPlayer state changes (playing/position/duration etc.). */
window.onPlayerStateChanged = function(json) {
    try {
        var state = JSON.parse(json);
        window.playerState.playing = state.playing;
        if (state.playing) {
            $('#player-loading').style.display = 'none';
        }
    } catch(e) {}
};

/** Called by native when user switches episode via SakuraPlayer control bar. */
window.onPlayerEpisodeChange = function(idx) {
    playEpisode(idx);
};

// ==================== Overlay close hook - release ExoPlayer ====================

(function() {
    if (window.__detailPlayerHooked) return;
    window.__detailPlayerHooked = true;

    // Hook closeOverlay to release ExoPlayer when detail overlay closes
    var origCloseOverlay = closeOverlay;
    closeOverlay = function() {
        var stack = tabOverlayStack[currentTab];
        var top = (stack && stack.length > 0) ? stack[stack.length - 1] : null;
        if (top === 'detail') {
            resetPlayer();
            // Hide native SakuraPlayer
            try { window.Sakura.setSakuraPlayerVisible(false); } catch(e) {}
        }
        return origCloseOverlay.apply(this, arguments);
    };

    // Hook switchTab to hide local player when switching away from a tab with detail open
    var origSwitchTab = switchTab;
    switchTab = function(tab) {
        var oldStack = tabOverlayStack[currentTab];
        var oldTop = (oldStack && oldStack.length > 0) ? oldStack[oldStack.length - 1] : null;
        if (oldTop === 'detail' && tab !== currentTab) {
            resetPlayer();
            try { window.Sakura.setSakuraPlayerVisible(false); } catch(e) {}
        }
        return origSwitchTab.apply(this, arguments);
    };
})();

function redownloadFromDetail() {
    if (!window.currentDetail?.isLocal) return;
    var ep = window.currentDetail.episodes?.find(function(e) { return e.index === window.playerState.currentEp; });
    if (!ep || !ep.path) { showToast('找不到当前剧集'); return; }
    if (!confirm('确定重新下载 ' + ep.name + '？\n将删除旧文件并重新获取下载。')) return;
    callNativeLegacy('redownloadLocal', JSON.stringify([ep.path])).then(function(count) {
        showToast('已加入重新下载队列: ' + count + ' 个文件');
    }).catch(handleApiError);
}
