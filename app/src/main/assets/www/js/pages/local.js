// ==================== Local File Browser (2-column grid, selection mode) ====================

// Use var so test __state proxy can access these
var localPath = '';
var localItems = [];
var localSelected = new Set();
var localSelectAll = false;
var localHistory = [];
window.localSelectMode = false;

var localLongPressTimer = null;

// ===== SETUP =====

let SettingsPrefs_dlPath = '';
function setDlPath(p) { SettingsPrefs_dlPath = p; }

// Override openDetail for local items from search/follow results (folders only)
const origOpenDetail = window.openDetail;
window.openDetail = function(videoId, isLocal, localPath) {
    if (isLocal && localPath) {
        browseInto(localPath);
    } else if (origOpenDetail) {
        origOpenDetail(videoId, isLocal, localPath);
    }
};

// ===== NAVIGATION =====

async function browseInto(path) {
    localPath = path || '';
    showLocalLoading();
    try {
        const data = await callNativeLegacy('browseLocalDir', localPath);
        renderLocalGrid(data);
        const effectivePath = localPath || '';
        const histLen = localHistory.length;
        if (histLen === 0 || localHistory[histLen - 1] !== effectivePath) {
            localHistory.push(effectivePath);
        }
    } catch(e) { handleApiError(e); }
}

// ===== RENDER =====

function renderLocalGrid(data) {
    localItems = data.items || [];
    localPath = data.path || '';
    window.localSelectMode = false;
    localSelected = new Set();
    localSelectAll = false;

    const dlPath = SettingsPrefs_dlPath || '/storage/emulated/0/SakuraAnime';

    // --- Breadcrumb (in separate #local-breadcrumb container) ---
    const bcEl = document.getElementById('local-breadcrumb');
    if (bcEl) {
        const relPath = data.path === dlPath ? '' : data.path.replace(dlPath, '').replace(/^\//, '');
        let bcHtml = '<div class="local-breadcrumb">';
        bcHtml += '<span class="local-crumb" data-path="">📂 樱花动漫</span>';
        if (relPath) {
            const parts = relPath.split('/').filter(Boolean);
            let acc = '';
            for (const p of parts) {
                acc += '/' + p;
                bcHtml += '<span class="local-crumb-sep">›</span>';
                bcHtml += '<span class="local-crumb" data-path="' + escHtml(dlPath + acc) + '">' + escHtml(p) + '</span>';
            }
        }
        bcHtml += '</div>';
        bcEl.innerHTML = bcHtml;
        bcEl.style.display = '';

        // Breadcrumb click handlers
        bcEl.querySelectorAll('.local-crumb').forEach(el => {
            el.addEventListener('click', () => {
                const targetPath = el.dataset.path;
                if (targetPath !== undefined) browseInto(targetPath);
            });
        });
    }

    // --- Card grid (only grid items, no breadcrumb inside) ---
    let html = '';

    // First card: "New Folder"
    html += '<div class="card new-folder-card" id="local-new-folder" onclick="createNewFolder()">';
    html += '<div class="card-img-wrap new-folder-wrap"><span class="new-folder-icon">+</span></div>';
    html += '<div class="card-info"><div class="card-title">新建文件夹</div></div>';
    html += '</div>';

    // Items
    for (let i = 0; i < localItems.length; i++) {
        const item = localItems[i];
        const isDir = item.isDir;
        const icon = isDir ? '📁' : '🎬';

        html += '<div class="card local-card" data-index="' + i + '" data-path="' + escHtml(item.path) + '" data-isdir="' + isDir + '"';
        html += ' ontouchstart="handleLocalTouchStart(event, ' + i + ')" ontouchend="handleLocalTouchEnd(event, ' + i + ')" onclick="handleLocalClick(' + i + ')">';
        html += '<div class="card-img-wrap">';
        html += '<div class="card-check"></div>';
        html += '<img class="local-cover-img" data-coverkey="' + escHtml(item.coverKey || '') + '" src="" style="display:none" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'\'">';
        html += '<div class="card-thumb-placeholder">' + icon + '</div>';
        html += '</div>';
        html += '<div class="card-info">';
        html += '<div class="card-title">' + escHtml(item.name) + '</div>';
        html += '<div class="card-meta">' + (isDir ? (item.episodeCount || 0) + ' 集' : (item.size || '')) + '</div>';
        html += '</div>';
        html += '</div>';
    }

    document.getElementById('local-grid').innerHTML = html;
    document.getElementById('local-empty').style.display = localItems.length > 0 ? 'none' : '';

    // Action bar (reset state, hidden by default)
    const abEl = document.getElementById('local-action-bar');
    if (abEl) {
        abEl.style.display = 'none';
        const saBtn = document.getElementById('btn-select-all');
        if (saBtn) saBtn.textContent = '全选';
        const rnBtn = document.getElementById('btn-rename');
        if (rnBtn) rnBtn.style.opacity = '0.5';
        const rdBtn = document.getElementById('btn-redownload');
        if (rdBtn) rdBtn.style.opacity = '0.5';
    }

    // Lazy-load covers
    requestAnimationFrame(() => loadVisibleCovers());
}

// ===== COVER LAZY LOAD =====

function loadVisibleCovers() {
    var imgs = document.querySelectorAll('.local-cover-img');
    imgs.forEach(function(img) {
        var key = img.dataset.coverkey;
        if (!key) return;
        loadCoverForImg(img, key);
    });
}

function loadCoverForImg(img, key) {
    callNativeLegacy('getLocalCover', key).then(function(path) {
        if (path) { img.src = path; img.style.display = ''; }
    }).catch(function() {});
}

// ===== SELECTION MODE =====

var localTouchStartY = 0;

function handleLocalTouchStart(e, index) {
    if (e.target.closest('.card-check')) return;
    localTouchStartY = e.touches[0].clientY;
    localLongPressTimer = setTimeout(() => {
        enterSelectMode();
        toggleSelect(index);
    }, 500);
}

function handleLocalTouchEnd(e, index) {
    clearTimeout(localLongPressTimer);
}

// Cancel long press if the user is scrolling
document.getElementById('local-grid').addEventListener('touchmove', function(e) {
    if (localLongPressTimer) {
        var dy = Math.abs(e.touches[0].clientY - localTouchStartY);
        if (dy > 10) clearTimeout(localLongPressTimer);
    }
}, { passive: true });

function handleLocalClick(index) {
    if (window.localSelectMode) {
        toggleSelect(index);
        return;
    }
    const item = localItems[index];
    if (!item) return;
    if (item.isDir) {
        browseInto(item.path);
    } else {
        openLocalDetail(item, index);
    }
}

function openLocalDetail(fileItem, index) {
    // Build episode list from all video files in the same directory
    const episodes = [];
    var dirCoverKey = '';
    localItems.forEach(function(item, i) {
        if (!item.isDir) {
            var epMatch = item.name.match(/第\s*(\d+)/i) || item.name.match(/[Ee][Pp]?\s*(\d+)/) || item.name.match(/[Ss]\d+[Ee](\d+)/) || item.name.match(/(\d+)/);
            var epNum = epMatch ? epMatch[1] : (episodes.length + 1);
            episodes.push({ index: episodes.length + 1, name: '第' + epNum + '集', path: item.path, localIndex: i });
        } else if (item.coverKey && !dirCoverKey) {
            dirCoverKey = item.coverKey;
        }
    });
    const currentEp = episodes.findIndex(function(ep) { return ep.localIndex === index; }) + 1;

    // Use directory coverKey to load cover lazily
    var coverUrl = '';
    if (dirCoverKey) {
        callNativeLegacy('getLocalCover', dirCoverKey).then(function(path) {
            if (path) {
                var img = document.getElementById('player-cover-img');
                if (img) img.src = path;
            }
        }).catch(function() {});
    }

    // Title reflects the episode the user tapped (episodes share one detail page but
    // each row is a separate file). playEpisode() updates the visible title per switch.
    var tappedEpTitle = (episodes[currentEp - 1] && episodes[currentEp - 1].name) ? episodes[currentEp - 1].name : fileItem.name;

    window.currentDetail = {
        videoId: 0,
        title: tappedEpTitle,
        coverUrl: coverUrl,
        description: '本地文件' + (fileItem.size ? ' · ' + fileItem.size : ''),
        tags: ['本地'],
        isLocal: true,
        episodes: episodes,
        totalEps: episodes.length
    };
    if (typeof resetPlayer === 'function') resetPlayer();
    window.playerState.currentEp = currentEp;
    window.playerState.playing = false;
    if (typeof renderDetail === 'function') renderDetail(window.currentDetail);
    openOverlay('detail');
    if (typeof playEpisode === 'function') playEpisode(currentEp);
}

function enterSelectMode() {
    window.localSelectMode = true;
    const gridEl = document.getElementById('local-grid');
    if (gridEl) gridEl.classList.add('select-mode');
    const abEl = document.getElementById('local-action-bar');
    if (abEl) abEl.style.display = 'flex';
    const nfEl = document.getElementById('local-new-folder');
    if (nfEl) nfEl.style.display = 'none';
    // Reset selection
    localSelected = new Set();
    localSelectAll = false;
    const saBtn = document.getElementById('btn-select-all');
    if (saBtn) saBtn.textContent = '全选';
    const rnBtn = document.getElementById('btn-rename');
    if (rnBtn) rnBtn.style.opacity = '0.5';
    const rdBtn = document.getElementById('btn-redownload');
    if (rdBtn) rdBtn.style.opacity = '0.5';
    updateCheckMarks();
}

function exitSelectMode() {
    window.localSelectMode = false;
    localSelected = new Set();
    const gridEl = document.getElementById('local-grid');
    if (gridEl) gridEl.classList.remove('select-mode');
    const abEl = document.getElementById('local-action-bar');
    if (abEl) abEl.style.display = 'none';
    const nfEl = document.getElementById('local-new-folder');
    if (nfEl) nfEl.style.display = '';
    updateCheckMarks();
}

function toggleSelect(index) {
    if (!window.localSelectMode) return;
    if (localSelected.has(index)) {
        localSelected.delete(index);
    } else {
        localSelected.add(index);
    }
    updateCheckMarks();
    updateActionBar();
}

function toggleSelectAll() {
    if (localSelectAll) {
        localSelected.clear();
        localSelectAll = false;
        const saBtn = document.getElementById('btn-select-all');
        if (saBtn) saBtn.textContent = '全选';
    } else {
        for (let i = 0; i < localItems.length; i++) localSelected.add(i);
        localSelectAll = true;
        const saBtn = document.getElementById('btn-select-all');
        if (saBtn) saBtn.textContent = '取消全选';
    }
    updateCheckMarks();
    updateActionBar();
}

function updateCheckMarks() {
    const cards = document.querySelectorAll('.local-card');
    cards.forEach(card => {
        const idx = parseInt(card.dataset.index);
        const check = card.querySelector('.card-check');
        if (!check) return;
        if (window.localSelectMode) {
            check.classList.toggle('checked', localSelected.has(idx));
        } else {
            check.classList.remove('checked');
        }
    });
}

function updateActionBar() {
    const count = localSelected.size;
    const renameBtn = document.getElementById('btn-rename');
    if (renameBtn) renameBtn.style.opacity = count === 1 ? '1' : '0.5';
    checkRedownloadAvailable();
}

async function checkRedownloadAvailable() {
    const selCount = localSelected.size;
    const rdBtn = document.getElementById('btn-redownload');
    if (!rdBtn) return;
    if (selCount === 0) {
        rdBtn.style.opacity = '0.5';
        return;
    }
    for (const idx of localSelected) {
        const item = localItems[idx];
        if (!item) continue;
        if (item.isDir) {
            rdBtn.style.opacity = '1';
            return;
        }
        try {
            const record = await callNativeLegacy('getDownloadRecord', item.path);
            if (record) {
                rdBtn.style.opacity = '1';
                return;
            }
        } catch(e) { /* ignore */ }
    }
    rdBtn.style.opacity = '0.5';
}

// ===== OPERATIONS =====

function createNewFolder() {
    const name = prompt('请输入文件夹名称：');
    if (!name || !name.trim()) return;
    callNativeLegacy('createLocalDir', localPath, name.trim()).then(ok => {
        if (ok) { browseInto(localPath); }
        else showToast('创建失败（可能已存在同名文件夹）');
    }).catch(handleApiError);
}

function localDelete() {
    if (localSelected.size === 0) return;
    if (!confirm('确定删除 ' + localSelected.size + ' 个文件/文件夹？此操作不可撤销。')) return;
    const paths = [];
    localSelected.forEach(i => paths.push(localItems[i].path));
    callNativeLegacy('deleteLocalFiles', JSON.stringify(paths)).then(() => {
        browseInto(localPath);
    }).catch(handleApiError);
}

function localRedownload() {
    if (localSelected.size === 0) return;
    if (!confirm('确定重新下载 ' + localSelected.size + ' 个文件/文件夹？\n将删除旧文件并重新获取下载。')) return;
    const paths = [];
    localSelected.forEach(function(i) { paths.push(localItems[i].path); });
    callNativeLegacy('redownloadLocal', JSON.stringify(paths)).then(function(count) {
        showToast('已加入重新下载队列: ' + count + ' 个文件');
        exitSelectMode();
    }).catch(handleApiError);
}

function localRename() {
    if (localSelected.size !== 1) return;
    const i = [...localSelected][0];
    const item = localItems[i];
    const ext = item.isDir ? '' : (item.name.includes('.') ? item.name.substring(item.name.lastIndexOf('.')) : '');
    const baseName = item.isDir ? item.name : item.name.substring(0, item.name.lastIndexOf('.'));
    const newName = prompt('重命名：', baseName);
    if (!newName || !newName.trim() || newName.trim() === baseName) return;
    const fullName = item.isDir ? newName.trim() : newName.trim() + ext;
    callNativeLegacy('renameLocalFile', item.path, fullName).then(ok => {
        if (ok) browseInto(localPath);
        else showToast('重命名失败');
    }).catch(handleApiError);
}

function localMove() {
    if (localSelected.size === 0) return;
    showLocalMovePicker(localPath, (targetDir) => {
        const paths = [];
        localSelected.forEach(i => paths.push(localItems[i].path));
        callNativeLegacy('moveLocalFiles', JSON.stringify(paths), targetDir).then(ok => {
            if (ok) browseInto(localPath);
            else showToast('移动失败');
        }).catch(handleApiError);
    });
}

// ===== MOVE PICKER =====

function showLocalMovePicker(currentDir, onSelect) {
    let movePath = '/storage/emulated/0/SakuraAnime';
    const overlay = document.createElement('div');
    overlay.id = 'move-picker-overlay';
    overlay.innerHTML = '<div class="move-picker">' +
      '<div class="move-picker-header">' +
        '<button class="move-picker-back" id="move-picker-back">← 返回</button>' +
        '<span class="move-picker-title">选择目标文件夹</span>' +
        '<button class="move-picker-confirm" id="move-picker-confirm">确认</button>' +
      '</div>' +
      '<div class="move-picker-path" id="move-picker-path"></div>' +
      '<div class="move-picker-list" id="move-picker-list"></div>' +
    '</div>';
    document.body.appendChild(overlay);

    async function loadMoveDir(path) {
        movePath = path;
        const pathEl = document.getElementById('move-picker-path');
        if (pathEl) pathEl.textContent = path;
        const data = await callNativeLegacy('browseLocalDir', path);
        const list = document.getElementById('move-picker-list');
        if (!list) return;
        list.innerHTML = (data.items || [])
            .filter(function(i) { return i.isDir; })
            .map(function(i) {
                return '<div class="move-picker-item" data-path="' + escHtml(i.path) + '" onclick="(function(el){el.parentElement.querySelectorAll(\'.move-picker-item\').forEach(function(e){e.classList.remove(\'selected\')});el.classList.add(\'selected\')})(this)">📁 ' + escHtml(i.name) + '</div>';
            }).join('');
    }

    loadMoveDir(movePath);

    const backBtn = document.getElementById('move-picker-back');
    if (backBtn) {
        backBtn.onclick = function() {
            const parent = movePath.substring(0, movePath.lastIndexOf('/')) || '/storage/emulated/0/SakuraAnime';
            loadMoveDir(parent);
        };
    }
    const confirmBtn = document.getElementById('move-picker-confirm');
    if (confirmBtn) {
        confirmBtn.onclick = function() {
            const selected = document.querySelector('.move-picker-item.selected');
            const targetDir = selected ? selected.dataset.path : movePath;
            if (targetDir === currentDir) { showToast('不能移动到当前目录'); return; }
            document.body.removeChild(overlay);
            onSelect(targetDir);
        };
    }
    overlay.onclick = function(e) {
        if (e.target === overlay) { document.body.removeChild(overlay); }
    };
}

// ===== HELPERS =====

function showLocalLoading() {
    const gridEl = document.getElementById('local-grid');
    if (gridEl) gridEl.innerHTML = '<div class="loading-spinner">加载中...</div>';
}

// ===== EXPORT TO GLOBAL (for handleBackPress and tests) =====
window.browseInto = browseInto;
window.browseDir = browseInto;  // backward compatibility alias
window.exitSelectMode = exitSelectMode;
window.enterSelectMode = enterSelectMode;
window.renderLocalGrid = renderLocalGrid;
window.loadVisibleCovers = loadVisibleCovers;
