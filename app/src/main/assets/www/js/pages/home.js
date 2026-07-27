// ==================== Home Tab ====================

let homeTab = 'local';

function initHomeTabs() {
    $$('.home-tab').forEach(tab => {
        tab.addEventListener('click', () => {
            homeTab = tab.dataset.htab;
            $$('.home-tab').forEach(t => t.classList.toggle('active', t.dataset.htab === homeTab));
            $('#local-grid').style.display = homeTab === 'local' ? '' : 'none';
            $('#follow-grid').style.display = homeTab === 'follow' ? '' : 'none';
            if (homeTab === 'local') loadLocalLibrary();
            else loadFollowList();
        });
    });
}

async function loadHome() {
    if (homeTab === 'local') loadLocalLibrary();
    else loadFollowList();
}

// ==================== Local Library ====================

async function loadLocalLibrary() {
    try {
        await browseInto('');
    } catch(e) {
        document.getElementById('local-grid').innerHTML = '';
        document.getElementById('local-empty').style.display = '';
    }
}

function renderGrid(gridId, items) {
    const el = $('#' + gridId);
    if (!el) return;
    el.innerHTML = items.map(renderCard).join('');
    const emptyEl = $('#local-empty');
    if (emptyEl && gridId === 'local-grid') emptyEl.style.display = items.length ? 'none' : '';
    const fEmptyEl = $('#follow-empty');
    if (fEmptyEl && gridId === 'follow-grid') fEmptyEl.style.display = items.length ? 'none' : '';
}

// Simple string hashCode
String.prototype.hashCode = function() {
    let h = 0;
    for (let i = 0; i < this.length; i++) {
        h = ((h << 5) - h) + this.charCodeAt(i);
        h |= 0;
    }
    return h;
};
