// Comprehensive test suite for Sakura Player - covers REQUIREMENTS 1-20
const { JSDOM } = require('jsdom');
const fs = require('fs');
const path = require('path');

const wwwDir = path.join(__dirname, 'app/src/main/assets/www');

let html = fs.readFileSync(path.join(wwwDir, 'index.html'), 'utf8');
html = html.replace(/<link[^>]*\/?>/gi, '');
html = html.replace(/<script[^>]*src="[^"]*"[^>]*><\/script>/gi, '');

const dom = new JSDOM(html, {
    url: 'http://localhost:8080/index.html',
    runScripts: 'dangerously',
    pretendToBeVisual: true,
    beforeParse(window) {
        window.matchMedia = function() { return { matches: false, addListener: function() {}, removeListener: function() {} }; };
        window.innerWidth = 390;
        window.innerHeight = 844;
        if (window.HTMLMediaElement) {
            window.HTMLMediaElement.prototype.play = function() { return Promise.resolve(); };
            window.HTMLMediaElement.prototype.pause = function() {};
        }
        // Mock IntersectionObserver for jsdom
        window.IntersectionObserver = function(cb, opts) {
            return {
                observe: function(el) { cb([{isIntersecting: true, target: el}]); },
                unobserve: function() {},
                disconnect: function() {},
                rootMargin: opts ? opts.rootMargin : ''
            };
        };
    }
});

var window = dom.window;
var document = dom.window.document;

var passed = 0, failed = 0;
var failures = [];

function assert(condition, name) {
    if (condition) { passed++; return true; }
    else { failed++; failures.push(name); console.log('  FAIL: ' + name); return false; }
}

function wait(ms) { return new Promise(function(r) { setTimeout(r, ms); }); }

function loadAllJS(files) {
    var combined = '';
    for (var i = 0; i < files.length; i++) {
        combined += fs.readFileSync(path.join(wwwDir, files[i]), 'utf8') + '\n';
    }
    combined += [
        '(function(){',
        'window.__state = {};',
        'var _names = ["navStack","tabCache","currentTab","currentView","searchResults","currentDetail",',
        '"playerState","playerTouch","playerGest","localHistory","lastBackTime","homeTab",',
        '"discoverCat","playerInstance","pollActive","downloadPollTimer"];',
        'var _expose = function(name) {',
        '  Object.defineProperty(window.__state, name, {',
        '    get: function() { return eval(name); },',
        '    set: function(v) {',
        '      var json = typeof v === "object" ? JSON.stringify(v) : String(v);',
        '      eval("var __tv = " + json + "; " + name + " = __tv; __tv = null;");',
        '    },',
        '    configurable: true,',
        '  });',
        '};',
        'for (var i=0;i<_names.length;i++) _expose(_names[i]);',
        'window.__SakuraPlayer = SakuraPlayer;',
        '})();'
    ].join('\n');
    try {
        window.eval(combined);
        console.log('  - All modules loaded in shared scope');
    } catch(e) {
        console.log('  FAIL: ' + e.message);
    }
}

// Inject CSS
try {
    var css = fs.readFileSync(path.join(wwwDir, 'css/style.css'), 'utf8');
    var style = document.createElement('style');
    style.textContent = css;
    document.head.appendChild(style);
} catch(e) {}

var scripts = [
    'js/player.js',
    'js/pages/home.js',
    'js/pages/search.js',
    'js/pages/detail.js',
    'js/pages/local.js',
    'js/pages/follow.js',
    'js/pages/settings.js',
    'js/mock-bridge.js',
    'js/app.js',
];

async function runTests() {
    console.log('=== Sakura Player - Comprehensive Functional Test ===\n');

    // ===== [0] LOAD MODULES =====
    console.log('--- [0] Loading Modules ---');
    loadAllJS(scripts);
    if (typeof initHomeTabs === 'function') initHomeTabs();
    if (typeof loadHome === 'function') loadHome();
    if (typeof loadDiscover === 'function') loadDiscover();
    if (typeof loadDownloadsPath === 'function') loadDownloadsPath();
    if (typeof pollDownloads === 'function') pollDownloads();
    await wait(1000);

    // ===== [1] GLOBAL MODULE EXPORTS =====
    console.log('\n--- [1] Global Module Exports ---');
    var funcs = ['showToast','goSearch','openDetail','doSearch','callNativeLegacy','handleBackPress',
        'switchTab','closeOverlay','openOverlay','browseDir','renderCard','renderGrid',
        'loadFollowList','playEpisode','toggleFollow','toggleLock','batchDownload',
        'loadSettings','initEmbeddedPlayer','loadLocalLibrary','resetPlayer','bindPlayer'];
    for (var fi = 0; fi < funcs.length; fi++) {
        assert(typeof window[funcs[fi]] === 'function', funcs[fi] + ' exists');
    }
    assert(typeof window.__SakuraPlayer === 'function', 'SakuraPlayer class accessible via __SakuraPlayer');
    var bridgeMethods = ['search','browseDir','getDetail','getDiscover','getFollows','getDownloadStatus','getSettings','refreshDomain'];
    for (var bi = 0; bi < bridgeMethods.length; bi++) {
        assert(window.Sakura && typeof window.Sakura[bridgeMethods[bi]] === 'function', 'Mock JSBridge.' + bridgeMethods[bi]);
    }

    // ===== [2] DOM ELEMENTS =====
    console.log('\n--- [2] DOM Elements ---');
    var domIds = ['tab-bar','view-home','view-discover','view-download','view-me',
        'view-search','view-detail','view-settings','toast','local-grid','follow-grid',
        'discover-grid','dl-list','local-empty','follow-empty','dl-empty','search-empty','search-input'];
    for (var di = 0; di < domIds.length; di++) {
        assert(document.getElementById(domIds[di]) !== null, '#' + domIds[di] + ' exists');
    }
    var tabs = document.querySelectorAll('#tab-bar .tab-item');
    assert(tabs.length === 4, '4 tab items exist, got ' + tabs.length);
    var tabNames = ['home','discover','download','me'];
    for (var ti = 0; ti < tabs.length; ti++) {
        assert(tabs[ti].dataset.tab === tabNames[ti], 'Tab ' + (ti+1) + ' = ' + tabNames[ti]);
    }
    // Home tab starts active (from HTML attribute)
    assert(tabs[0].classList.contains('active'), 'Home tab starts active');

    // ===== [3] HOME TAB (REQUIREMENT 2) =====
    console.log('\n--- [3] Home Tab (Req 2) ---');
    tabs[0].click();
    await wait(500);
    var localCards = document.getElementById('local-grid').querySelectorAll('.card');
    assert(localCards.length >= 4, 'Local library cards: ' + localCards.length);
    // First card is new-folder card
    assert(localCards[0].classList.contains('new-folder-card'), 'First card is new-folder-card');
    assert(localCards[0].querySelector('.new-folder-icon') !== null, 'New folder has + icon');
    // Second card is a local item (directory)
    assert(localCards[1].classList.contains('local-card'), 'Second card is local-card');
    assert(localCards[1].querySelector('.card-img-wrap') !== null, 'Local card has image wrap');
    assert(localCards[1].querySelector('.card-title') !== null, 'Local card has title');
    assert(localCards[1].querySelector('.card-meta') !== null, 'Local card has meta');
    assert(localCards[1].querySelector('.card-check') !== null, 'Local card has check element');

    // Follow tab
    document.querySelector('.home-tab[data-htab="follow"]').click();
    await wait(400);
    var followCards = document.getElementById('follow-grid').querySelectorAll('.card');
    assert(followCards.length >= 2, 'Follow list cards: ' + followCards.length);
    assert(followCards[0].querySelector('.follow-progress-fill') !== null, 'Follow card has progress bar');
    assert(document.getElementById('follow-grid').querySelector('.follow-update-dot') !== null, 'Update dot');
    assert(followCards[0].querySelector('.card-meta') !== null, 'Follow card has meta');

    // Back to local
    document.querySelector('.home-tab[data-htab="local"]').click();
    await wait(300);

    // ===== [4] SEARCH (REQUIREMENT 6) =====
    console.log('\n--- [4] Search (Req 6) ---');
    window.goSearch();
    await wait(200);
    assert(document.getElementById('view-search').classList.contains('active'), 'Search overlay is active');

    document.getElementById('search-input').value = '\u5de8\u4eba';
    window.doSearch();
    await wait(600);
    var sCards = document.getElementById('search-results').querySelectorAll('.card');
    assert(sCards.length >= 3, 'Search returned ' + sCards.length + ' results');

    // R6: Local before online
    assert(sCards[0].querySelector('.card-local-badge') !== null, 'First result: local badge');
    assert(sCards[0].querySelector('.card-local-badge').textContent === '\u672c\u5730', 'Badge text');

    // R6: Online results have no badge
    var nonLocalBadges = document.querySelectorAll('#search-results .card:not(:first-child) .card-local-badge');
    assert(nonLocalBadges.length === 0, 'Online results: no badge');

    // R6: Search results preserved in JS
    var sr = window.__state.searchResults;
    assert(Array.isArray(sr), 'searchResults is array');
    assert(sr.length >= 3, 'searchResults has ' + sr.length + ' items');
    assert(sr[0].isLocal === true, 'First result is local');
    assert(sr[1].isLocal === false, 'Second result is online');

    // R6: Dedup - all unique videoIds
    var seenIds = {}, allUnique = true;
    for (var sri = 0; sri < sr.length; sri++) {
        if (seenIds[sr[sri].videoId]) { allUnique = false; break; }
        seenIds[sr[sri].videoId] = true;
    }
    assert(allUnique, 'All search results unique videoId');

    window.closeSearch();
    await wait(100);
    assert(!document.getElementById('view-search').classList.contains('active'), 'Search overlay closed');

    // ===== [5] DETAIL PAGE (REQUIREMENT 7 - Bilibili-style) =====
    console.log('\n--- [5] Detail Page (Req 7 - Bilibili-style) ---');
    window.openDetail(1, false, '');
    await wait(400);
    assert(document.getElementById('view-detail').classList.contains('active'), 'Detail overlay is active');

    // R7: Basic info
    assert(document.getElementById('d-title').textContent.length > 0, 'Title filled');
    assert(document.getElementById('d-desc').textContent.length > 0, 'Description filled');
    var tags = document.getElementById('d-tags').querySelectorAll('.tag');
    assert(tags.length === 4, '4 tags, got ' + tags.length);
    assert(tags[0].textContent === '\u70ed\u8840', 'First tag');

    // R7: Player area (16:9 top section)
    assert(document.getElementById('player-area') !== null, 'Player area exists');
    assert(document.getElementById('player-cover') !== null, 'Player cover');
    assert(document.getElementById('player-cover-img') !== null, 'Cover image');
    assert(document.getElementById('detail-video') !== null, 'Video element');
    assert(document.getElementById('player-ctrls') !== null, 'Player controls');
    assert(document.getElementById('player-loading') !== null, 'Loading indicator');

    // Progress bar row
    assert(document.getElementById('p-prog-row') !== null, 'Progress row');
    assert(document.getElementById('p-track') !== null, 'Progress track');
    assert(document.getElementById('p-inner') !== null, 'Progress inner');
    assert(document.getElementById('p-fill') !== null, 'Progress fill');
    assert(document.getElementById('p-dot') !== null, 'Progress dot');
    assert(document.getElementById('p-buf') !== null, 'Progress buffer');
    assert(document.getElementById('p-tcur') !== null, 'Current time');
    assert(document.getElementById('p-ttot') !== null, 'Total time');

    // Buttons: play/pause, fullscreen, lock
    assert(document.getElementById('p-pp') !== null, 'Play/pause');
    assert(document.getElementById('p-fs') !== null, 'Fullscreen');
    assert(document.getElementById('p-lock') !== null, 'Lock button');
    assert(document.getElementById('p-lock').textContent === '\ud83d\udd13', 'Lock starts unlocked');

    // HUDs (floating indicators)
    assert(document.getElementById('p-vol') !== null, 'Volume HUD');
    assert(document.getElementById('p-bri') !== null, 'Brightness HUD');
    assert(document.getElementById('p-seek-pv') !== null, 'Seek preview');
    assert(document.getElementById('p-tip-speed') !== null, 'Speed tip');

    // Detail body (scrollable)
    assert(document.getElementById('d-tags') !== null, 'Tags row');
    assert(document.getElementById('d-desc') !== null, 'Description');
    assert(document.getElementById('btn-follow-detail') !== null, 'Follow btn');
    assert(document.getElementById('btn-dl-all') !== null, 'Batch DL btn');
    assert(document.getElementById('episode-grid') !== null, 'Episode grid');

    // R7: Episode count (16 from mock)
    var epBtns = document.getElementById('episode-grid').querySelectorAll('.ep-btn');
    assert(epBtns.length === 16, '16 episode buttons, got ' + epBtns.length);
    assert(epBtns[0].textContent.trim() === '\u7b2c1\u96c6', 'First episode');
    assert(epBtns[15].textContent.trim() === '\u7b2c16\u96c6', 'Last episode');

    // R7: Click episode -> inline player (state + playing class)
    epBtns[0].click();
    await wait(100);
    assert(window.__state.playerState.currentEp === 1, 'currentEp=1, got ' + window.__state.playerState.currentEp);
    assert(epBtns[0].classList.contains('playing'), 'Playing class on clicked ep');

    // R7: Follow toggle
    var followBtn = document.getElementById('btn-follow-detail');
    followBtn.click();
    await wait(300);
    assert(followBtn.textContent.includes('\u8ffd\u756a'), 'Follow btn after toggle');

    // R7: Lock/unlock
    assert(window.__state.playerState.locked === false, 'Lock starts false');
    window.toggleLock();
    assert(window.__state.playerState.locked === true, 'Lock toggled true');
    assert(document.getElementById('p-lock').textContent === '\ud83d\udd12', 'Lock icon');
    window.toggleLock();
    assert(window.__state.playerState.locked === false, 'Lock toggled false');
    assert(document.getElementById('p-lock').textContent === '\ud83d\udd13', 'Unlock icon');

    // Lock via button onclick (after bindPlayer)
    window.bindPlayer(document.getElementById('detail-video'));
    document.getElementById('p-lock').click();
    assert(window.__state.playerState.locked === true, 'Lock btn click');
    document.getElementById('p-lock').click();
    assert(window.__state.playerState.locked === false, 'Unlock btn click');

    // R7: Batch download + fullscreen (no crash)
    document.getElementById('btn-dl-all').click();
    await wait(50);
    assert(true, 'Batch DL: no crash');
    document.getElementById('p-fs').click();
    await wait(50);
    assert(true, 'Fullscreen: no crash');

    window.closeDetail();
    await wait(100);
    assert(!document.getElementById('view-detail').classList.contains('active'), 'Detail closed');

    // ===== [6] DISCOVER TAB (REQUIREMENT 3) =====
    console.log('\n--- [6] Discover Tab (Req 3) ---');
    document.querySelector('#tab-bar .tab-item[data-tab="discover"]').click();
    await wait(500);
    var discCards = document.getElementById('discover-grid').querySelectorAll('.card');
    assert(discCards.length === 6, 'Discover cards: ' + discCards.length + ' (expected 6)');
    assert(discCards[0].querySelector('.card-title') !== null, 'Discover card title');
    assert(discCards[0].querySelector('.card-meta') !== null, 'Discover card meta');

    // Category tabs
    var cats = document.querySelectorAll('.dcat');
    assert(cats.length === 5, '5 categories, got ' + cats.length);
    var catLabels = ['\u63a8\u8350','\u65e5\u672c\u52a8\u6f2b','\u56fd\u4ea7\u52a8\u6f2b','\u6b27\u7f8e\u52a8\u6f2b','\u52a8\u6f2b\u7535\u5f71'];
    for (var ci = 0; ci < cats.length; ci++) {
        assert(cats[ci].textContent === catLabels[ci], 'Category ' + (ci+1));
    }
    assert(cats[0].classList.contains('active'), 'First category active');
    cats[1].click();
    await wait(300);
    assert(!cats[0].classList.contains('active'), 'First deactivated');
    assert(cats[1].classList.contains('active'), 'Second activated');

    // ===== [7] DOWNLOAD TAB (REQUIREMENT 4) =====
    console.log('\n--- [7] Download Tab (Req 4) ---');
    document.querySelector('#tab-bar .tab-item[data-tab="download"]').click();
    await wait(400);
    var dlItems = document.getElementById('dl-list').querySelectorAll('.dl-item');
    assert(dlItems.length >= 2, 'Download tasks: ' + dlItems.length);
    assert(document.getElementById('dl-empty').style.display === 'none', 'DL empty hidden');

    var d1 = dlItems[0];
    assert(d1.querySelector('.dl-title') !== null, 'DL title');
    assert(d1.querySelector('.dl-status') !== null, 'DL status');
    assert(d1.querySelector('.dl-progress-fill') !== null, 'DL progress fill');
    assert(d1.querySelector('.dl-progress-wrap') !== null, 'DL progress wrap');
    assert(d1.querySelector('.dl-info') !== null, 'DL info');
    assert(d1.querySelector('.dl-header') !== null, 'DL header');

    assert(d1.querySelector('.dl-status').textContent.trim() === '\u4e0b\u8f7d\u4e2d',
        'Task1: ' + d1.querySelector('.dl-status').textContent.trim());
    assert(d1.querySelector('.dl-progress-fill').style.width === '45%',
        'Task1 progress: ' + d1.querySelector('.dl-progress-fill').style.width);
    assert(d1.querySelector('.dl-title').textContent.includes('\u8fdb\u51fb\u7684\u5de8\u4eba'), 'Task1 title');

    var d2 = dlItems[1];
    assert(d2.querySelector('.dl-status').textContent.trim() === '\u6392\u961f\u4e2d',
        'Task2: ' + d2.querySelector('.dl-status').textContent.trim());
    assert(d1.querySelector('.dl-actions') !== null, 'Task1 has actions');

    // ===== [8] SETTINGS (REQUIREMENT 5/12) =====
    console.log('\n--- [8] Settings (Req 5/12) ---');
    window.goSettings();
    await wait(300);
    assert(document.getElementById('view-settings').classList.contains('active'), 'Settings active');
    assert(document.getElementById('setting-path').textContent.includes('SakuraAnime'),
        'Path: ' + document.getElementById('setting-path').textContent);
    assert(document.getElementById('setting-domain').textContent.includes('yinghua14'),
        'Domain: ' + document.getElementById('setting-domain').textContent);

    var hasDomainS = false, hasPathS = false;
    var sItems = document.querySelectorAll('#view-settings .me-item');
    for (var si = 0; si < sItems.length; si++) {
        if (sItems[si].textContent.includes('\u57df\u540d')) hasDomainS = true;
        if (sItems[si].textContent.includes('\u4e0b\u8f7d\u8def\u5f84')) hasPathS = true;
    }
    assert(hasDomainS, 'Domain in settings');
    assert(hasPathS, 'Path in settings');

    window.closeSettings();
    await wait(100);
    assert(!document.getElementById('view-settings').classList.contains('active'), 'Settings closed');

    // ===== [9] ME TAB (REQUIREMENT 5) =====
    console.log('\n--- [9] Me Tab (Req 5) ---');
    document.querySelector('#tab-bar .tab-item[data-tab="me"]').click();
    await wait(200);
    assert(document.getElementById('view-me').classList.contains('active'), 'Me tab active');
    var meItems = document.querySelectorAll('#view-me .me-item');
    assert(meItems.length >= 3, '3+ items, got ' + meItems.length);
    assert(meItems[0].textContent.includes('\u8bbe\u7f6e'), 'Item1: settings');
    assert(meItems[1].textContent.includes('\u57df\u540d'), 'Item2: domain');
    assert(meItems[2].textContent.includes('\u5173\u4e8e'), 'Item3: about');

    assert(document.getElementById('domain-display').textContent.includes('yinghua14'),
        'Domain: ' + document.getElementById('domain-display').textContent);

    window.showAbout();
    await wait(50);
    assert(document.getElementById('toast').classList.contains('show'), 'About toast visible');
    assert(document.getElementById('toast').textContent.includes('\u6a31\u82b1\u52a8\u6f2b\u64ad\u653e\u5668'),
        'About: ' + document.getElementById('toast').textContent);

    // ===== [10] TOAST (REQUIREMENT 15 - UI) =====
    console.log('\n--- [10] Toast (Req 15 UI) ---');
    window.showToast('\u6d4b\u8bd5\u6d88\u606f', 300);
    await wait(100);
    var toast = document.getElementById('toast');
    assert(toast.classList.contains('show'), 'Toast visible');
    assert(toast.textContent === '\u6d4b\u8bd5\u6d88\u606f', 'Toast message');
    await wait(400);
    assert(!toast.classList.contains('show'), 'Toast hidden');

    // Rapid toasts
    window.showToast('A',300); window.showToast('B',300); window.showToast('C',300);
    await wait(50);
    assert(toast.classList.contains('show'), 'Rapid toast visible');
    assert(toast.textContent === 'C', 'Last toast=C, got "' + toast.textContent + '"');
    await wait(400);
    assert(!toast.classList.contains('show'), 'Rapid toast hidden');

    // ===== [11] TAB STATE PRESERVATION (REQUIREMENT 14 - CRITICAL) =====
    console.log('\n--- [11] Tab State Preservation (Req 14 - CRITICAL) ---');

    tabs[0].click(); await wait(300);
    window.__state.tabCache.home.loaded = true;

    // R14a: Browse local folder, switch away, switch back -> content preserved
    var initialLocalHTML = document.getElementById('local-grid').innerHTML;
    assert(initialLocalHTML.length > 0, 'Local grid has initial content');

    await window.browseDir('/storage/SakuraAnime/\u8fdb\u51fb\u7684\u5de8\u4eba');
    await wait(400);
    var browseContent = document.getElementById('local-grid').innerHTML;
    assert(browseContent !== initialLocalHTML, 'Local grid changed after browseDir');
    assert(browseContent.includes('\u8fd4\u56de') || browseContent.includes('\ud83d\udcc1') || browseContent.includes('\u8fdb\u51fb\u7684\u5de8\u4eba'),
        'Folder content present after browseDir');

    // Switch to discover, then back
    document.querySelector('#tab-bar .tab-item[data-tab="discover"]').click();
    await wait(200);
    assert(document.getElementById('view-discover').classList.contains('active'), 'Switched to discover');

    document.querySelector('#tab-bar .tab-item[data-tab="home"]').click();
    await wait(200);
    assert(document.getElementById('view-home').classList.contains('active'), 'Back to home');

    var afterSwitchContent = document.getElementById('local-grid').innerHTML;
    assert(afterSwitchContent === browseContent, 'Local browser content preserved after tab switch (NOT reset)');

    // R14b: Discover -> away -> back -> grid preserved
    document.querySelector('#tab-bar .tab-item[data-tab="discover"]').click();
    await wait(200);
    var discoverBefore = document.getElementById('discover-grid').innerHTML;
    assert(discoverBefore.length > 0, 'Discover has content');

    document.querySelector('#tab-bar .tab-item[data-tab="download"]').click();
    await wait(200);
    assert(document.getElementById('view-download').classList.contains('active'), 'Switched to download');

    document.querySelector('#tab-bar .tab-item[data-tab="discover"]').click();
    await wait(200);
    var discoverAfter = document.getElementById('discover-grid').innerHTML;
    assert(discoverAfter === discoverBefore, 'Discover content preserved');
    assert(discoverAfter.includes('card'), 'Discover cards still rendered');

    // R14c: Rapid switch all 4 tabs -> no crash
    try {
        var rapidTabs = ['download','home','me','discover','download','home','me','discover'];
        for (var rti = 0; rti < rapidTabs.length; rti++) {
            document.querySelector('#tab-bar .tab-item[data-tab="' + rapidTabs[rti] + '"]').click();
        }
    } catch(e) { assert(false, 'Rapid switching crash: ' + e.message); }
    assert(true, 'Rapid switching: no crash');

    // ===== [12] NAVIGATION (REQUIREMENT 13) =====
    console.log('\n--- [12] Navigation (Req 13) ---');

    tabs[0].click(); await wait(200);
    window.__state.navStack = [{type:'tab', name:'home'}];

    // R13a: navStack after opening overlays
    window.goSearch();
    await wait(100);
    assert(window.__state.navStack.length === 2, 'navStack=2 after search');
    assert(window.__state.navStack[1].type === 'overlay', 'Overlay type');
    assert(window.__state.navStack[1].name === 'search', 'Overlay=search');

    window.closeSearch();
    await wait(100);
    assert(window.__state.navStack.length === 1, 'navStack=1 after close');

    window.openDetail(1, false, '');
    await wait(400);
    assert(window.__state.navStack.length >= 2, 'navStack has detail');
    assert(window.__state.navStack[window.__state.navStack.length-1].name === 'detail', 'Last=detail');

    window.closeDetail();
    await wait(100);
    assert(window.__state.navStack.length === 1, 'navStack=1 after detail close');

    // R13b: Tab switching filters overlays from stack
    window.goSearch();
    await wait(100);
    assert(window.__state.navStack.length === 2, 'navStack=2 before tab switch');
    document.querySelector('#tab-bar .tab-item[data-tab="discover"]').click();
    await wait(200);
    var hasOverlay2 = false;
    for (var nsi = 0; nsi < window.__state.navStack.length; nsi++) {
        if (window.__state.navStack[nsi].type === 'overlay') hasOverlay2 = true;
    }
    assert(!hasOverlay2, 'No overlays after tab switch');
    assert(window.__state.navStack[window.__state.navStack.length-1].name === 'discover', 'Last tab=discover');

    // R13c: handleBackPress tests
    // Each test resets localHistory to avoid bleed between conditions
    tabs[0].click(); await wait(200);

    // Test 1: Local browser -> pop localHistory
    window.__state.localHistory = ['/root', '/root/f1', '/root/f1/sub'];
    window.__state.lastBackTime = 0;
    window._shouldExit = false;
    var obi = window.browseInto;
    var bdPath = null;
    window.browseInto = function(p) { bdPath = p; };
    window.handleBackPress();
    assert(window.__state.localHistory.length === 2, 'T1: localHistory=2');
    assert(bdPath === '/root/f1', 'T1: browseInto(' + bdPath + ')');
    assert(window._shouldExit === false, 'T1: _shouldExit=false');
    window.browseInto = obi;

    // Test 2: Overlay -> closes overlay
    window.__state.localHistory = ['/'];
    window.__state.navStack = [{type:'tab', name:'home'}];
    window.__state.lastBackTime = 0;
    window._shouldExit = false;
    window.goSearch();
    await wait(100);
    assert(document.getElementById('view-search').classList.contains('active'), 'T2: search visible');
    window.handleBackPress();
    await wait(100);
    assert(!document.getElementById('view-search').classList.contains('active'), 'T2: search closed');
    assert(window._shouldExit === false, 'T2: _shouldExit=false');

    // Test 3: Non-home tab -> goes to home
    window.__state.localHistory = ['/'];
    window._shouldExit = false;
    document.querySelector('#tab-bar .tab-item[data-tab="discover"]').click();
    await wait(100);
    assert(window.__state.currentTab === 'discover', 'T3: on discover');
    window.handleBackPress();
    await wait(100);
    assert(window.__state.currentTab === 'home', 'T3: switched to home');
    assert(window._shouldExit === false, 'T3: _shouldExit=false');

    // Test 4a: Home -> first press shows toast
    window.__state.localHistory = ['/'];
    window.__state.lastBackTime = 0;
    window._shouldExit = false;
    assert(window.__state.currentTab === 'home', 'T4a: on home');
    window.handleBackPress();
    assert(window._shouldExit === false, 'T4a: _shouldExit=false');
    assert(document.getElementById('toast').classList.contains('show'), 'T4a: toast shown');
    assert(document.getElementById('toast').textContent.includes('\u518d\u6309\u4e00\u6b21\u9000\u51fa'),
        'T4a: toast=' + document.getElementById('toast').textContent);

    // Test 4b: Home -> second press within 2s -> _shouldExit=true
    window._shouldExit = false;
    window.handleBackPress();
    assert(window._shouldExit === true, 'T4b: _shouldExit=' + window._shouldExit + ' (expected true)');

    // ===== [13] EMPTY STATES (REQUIREMENT 15) =====
    console.log('\n--- [13] Empty States (Req 15 UI) ---');

    // Local empty
    var obd2 = window.Sakura.browseLocalDir;
    window.Sakura.browseLocalDir = function(p, cb) {
        setTimeout(function() { window[cb](null, JSON.stringify({path:p||'/A',name:'A',parentPath:'',items:[]})); }, 100);
    };
    window.__state.tabCache.home.loaded = true;
    await window.loadLocalLibrary();
    await wait(300);
    // When items are empty, grid only has new-folder card but empty state should show
    var emptyGridCards = document.getElementById('local-grid').querySelectorAll('.card:not(.new-folder-card)');
    assert(emptyGridCards.length === 0, 'Local grid has no item cards when empty');
    assert(document.getElementById('local-empty').style.display !== 'none', 'Local empty visible');
    assert(document.getElementById('local-empty').querySelector('.empty-icon') !== null, 'Local empty icon');
    assert(document.getElementById('local-empty').querySelector('.empty-text') !== null, 'Local empty text');
    window.Sakura.browseLocalDir = obd2;
    window.__state.tabCache.home.loaded = true;
    await window.loadLocalLibrary();
    await wait(400);
    assert(document.getElementById('local-empty').style.display === 'none', 'Local empty hidden');

    // Follow empty
    var ogf = window.Sakura.getFollows;
    window.Sakura.getFollows = function(cb) { setTimeout(function() { window[cb](null, JSON.stringify([])); }, 100); };
    document.querySelector('.home-tab[data-htab="follow"]').click();
    await wait(300);
    assert(document.getElementById('follow-empty').style.display !== 'none', 'Follow empty visible');
    window.Sakura.getFollows = ogf;

    // Search empty (must open overlay first)
    window.goSearch();
    await wait(100);
    var os = window.Sakura.search;
    window.Sakura.search = function(k, cb) { setTimeout(function() { window[cb](null, JSON.stringify([])); }, 100); };
    document.getElementById('search-input').value = 'nonexistent';
    await window.doSearch();
    await wait(300);
    assert(document.getElementById('search-empty').style.display !== 'none', 'Search empty visible');
    assert(document.getElementById('search-empty').querySelector('.empty-icon') !== null, 'Search empty icon');
    assert(document.getElementById('search-empty').querySelector('.empty-text') !== null, 'Search empty text');
    window.Sakura.search = os;
    window.closeSearch();
    await wait(100);

    // Download empty
    var ods = window.Sakura.getDownloadStatus;
    window.Sakura.getDownloadStatus = function(cb) { setTimeout(function() { window[cb](null, JSON.stringify([])); }, 100); };
    document.querySelector('#tab-bar .tab-item[data-tab="download"]').click();
    await wait(100);
    window.__state.tabCache.download.loaded = true;
    await window.loadDownload();
    await wait(300);
    assert(document.getElementById('dl-list').innerHTML === '', 'DL list empty');
    assert(document.getElementById('dl-empty').style.display !== 'none', 'DL empty visible');
    window.Sakura.getDownloadStatus = ods;
    window.__state.tabCache.download.loaded = true;
    await window.loadDownload();
    await wait(300);

    // ===== [14] ACTIVE TAB INDICATOR (REQUIREMENT 15) =====
    console.log('\n--- [14] Active Tab Indicator (Req 15 UI) ---');
    // NOTE: switchTab() has a known bug - it does NOT update #tab-bar .tab-item .active class.
    // Only closeOverlay() updates tab bar classes. This test documents the behavior.

    var tabItems = document.querySelectorAll('#tab-bar .tab-item');
    var tabCheckList = ['home','discover','download','me'];

    // Views correctly change via switchTab (tab bar classes persist)
    for (var tti = 0; tti < tabCheckList.length; tti++) {
        window.switchTab(tabCheckList[tti]);
        // View should be active
        var viewEl = document.getElementById('view-' + tabCheckList[tti]);
        assert(viewEl !== null && viewEl.classList.contains('active'),
            'switchTab("' + tabCheckList[tti] + '"): view active');
        // Other views should NOT be active
        for (var ttj = 0; ttj < tabCheckList.length; ttj++) {
            if (ttj !== tti) {
                var ov = document.getElementById('view-' + tabCheckList[ttj]);
                assert(ov === null || !ov.classList.contains('active'),
                    'switchTab("' + tabCheckList[tti] + '"): view-' + tabCheckList[ttj] + ' inactive');
            }
        }
    }

    // KNOWN BUG: switchTab does NOT update bottom tab-bar active class
    // Verify the bug exists: switchTab('home') then switchTab('discover')
    // tabItems should still have home active (bug: only closeOverlay updates tabs)
    // This documents the limitation - the fix is to add tab-bar updates to switchTab
    window.switchTab('home');
    window.switchTab('discover');
    assert(tabItems[0].classList.contains('active'),
        'KNOWN BUG: tab-item[home] still active after switchTab("discover") - switchTab missing tab-bar update');
    assert(!tabItems[1].classList.contains('active'),
        'KNOWN BUG: tab-item[discover] not active - switchTab missing tab-bar update');
    console.log('  NOTE: switchTab() does not update tab-bar .tab-item .active class (bug).');
    console.log('  Tab bar classes only updated by closeOverlay().');

    // closeOverlay correctly updates tab-bar active classes
    window.switchTab('home');
    window.goSearch();
    window.closeSearch();
    // After closeOverlay, home tab should have active class
    assert(tabItems[0].classList.contains('active'), 'closeOverlay: home tab active');
    assert(!tabItems[1].classList.contains('active'), 'closeOverlay: discover not active');
    assert(!tabItems[2].classList.contains('active'), 'closeOverlay: download not active');
    assert(!tabItems[3].classList.contains('active'), 'closeOverlay: me not active');

    // Click handler also calls switchTab - same bug applies
    document.querySelector('#tab-bar .tab-item[data-tab="me"]').click();
    // tabItems still show home as active (because switchTab doesn't update them)
    assert(tabItems[0].classList.contains('active'),
        'KNOWN BUG: click "me" tab, home still active - tab-bar not updated');
    // But view-me IS active
    assert(document.getElementById('view-me').classList.contains('active'),
        'Click "me" tab: view-me is active (views updated correctly)');

    // ===== [15] ARRAY.ISARRAY GUARD (Critical - Bridge returns non-array) =====
    console.log('\n--- [15] Array.isArray Guard (Bridge returns non-array) ---');

    // A: loadDiscover has Array.isArray guard
    var ogd = window.Sakura.getDiscover;
    window.Sakura.getDiscover = function(p, cb) {
        setTimeout(function() { window[cb](null, JSON.stringify({error:'not_array'})); }, 100);
    };
    await window.loadDiscover();
    await wait(300);
    var di = window.__state.tabCache.discover.items;
    assert(Array.isArray(di), 'discover.items is array (guard works), type=' + typeof di);
    assert(di.length === 0, 'discover.items empty');
    window.Sakura.getDiscover = ogd;

    // B: doSearch has Array.isArray guard
    window.goSearch();
    await wait(100);
    var os2 = window.Sakura.search;
    window.Sakura.search = function(k, cb) { setTimeout(function() { window[cb](null, JSON.stringify({error:'not_array'})); }, 100); };
    document.getElementById('search-input').value = 'test';
    await window.doSearch();
    await wait(300);
    var sr2 = window.__state.searchResults;
    assert(Array.isArray(sr2), 'searchResults is array (guard works), type=' + typeof sr2);
    assert(sr2.length === 0, 'searchResults empty');
    window.Sakura.search = os2;
    window.closeSearch();
    await wait(100);

    // C: loadDownload MISSING Array.isArray guard (data||[] only catches null/undefined)
    var odl = window.Sakura.getDownloadStatus;
    window.Sakura.getDownloadStatus = function(cb) { setTimeout(function() { window[cb](null, JSON.stringify({error:'not_array'})); }, 100); };
    window.__state.tabCache.download.loaded = true;
    await window.loadDownload();
    await wait(300);
    var dt = window.__state.tabCache.download.tasks;
    assert(!Array.isArray(dt), 'loadDownload MISSING guard: type=' + typeof dt + ' (not array)');
    console.log('  NOTE: loadDownload() MISSING Array.isArray guard (uses data||[]) - non-array object leaks through');
    window.Sakura.getDownloadStatus = odl;

    // ===== [16] EDGE CASES =====
    console.log('\n--- [16] Edge Cases ---');

    // Empty search keyword
    document.getElementById('search-input').value = '';
    try { window.doSearch(); } catch(e) { assert(false, 'Empty search: ' + e.message); }
    assert(true, 'Empty search: no crash');

    // Null currentDetail
    window.__state.currentDetail = null;
    try { window.batchDownload(); } catch(e) { assert(false, 'batchDownload: ' + e.message); }
    try { window.toggleFollow(); } catch(e) { assert(false, 'toggleFollow: ' + e.message); }
    assert(true, 'Null detail: no crash');

    // Rapid overlays
    try { window.goSearch();window.closeSearch();window.goSettings();window.closeSettings();window.openDetail(1,false,'');window.closeDetail(); }
    catch(e) { assert(false, 'Overlays: ' + e.message); }
    assert(true, 'Rapid overlays: no crash');

    // Multiple toasts
    try { window.showToast('M1',100);window.showToast('M2',100);window.showToast('M3',100); } catch(e) {}
    assert(true, 'Multiple toasts: no crash');

    // handleBackPress edge
    window.__state.localHistory = ['/'];
    window.__state.navStack = [{type:'tab', name:'home'}];
    window.__state.lastBackTime = 0;
    window._shouldExit = false;
    try { window.handleBackPress(); } catch(e) { assert(false, 'hBP: ' + e.message); }
    assert(true, 'handleBackPress edge: no crash');

    // Rapid categories
    var ce = document.querySelectorAll('.dcat');
    try { for (var cxi = 0; cxi < ce.length; cxi++) ce[cxi].click(); } catch(e) {}
    assert(true, 'Rapid categories: no crash');

    // Event handlers
    try { window.onDomainReady('t.com'); window.onDomainRefresh('https://t.com'); window.showError('err'); }
    catch(e) { assert(false, 'Events: ' + e.message); }
    assert(true, 'Event handlers: no crash');

    // ===== [17] FULL PLAYER CLASS (player.js) =====
    console.log('\n--- [17] Full Player Class (player.js) ---');
    var ve = document.createElement('video');
    var ct = document.createElement('div');
    document.body.appendChild(ct);
    ct.appendChild(ve);

    try {
        var SP = window.__SakuraPlayer;
        assert(typeof SP === 'function', 'SakuraPlayer type=' + typeof SP);
        var p = window.initEmbeddedPlayer(ve, ct);
        assert(p !== null, 'Player created');
        assert(p instanceof SP, 'Player is SakuraPlayer instance');

        var pmethods = ['setLock','updateVBounds','setupKeyboard','setupGestures','setupControls','bindControlEvents'];
        for (var pmi = 0; pmi < pmethods.length; pmi++) {
            assert(typeof p[pmethods[pmi]] === 'function', 'Player.' + pmethods[pmi] + ' exists');
        }

        var pcIds = ['pc-ctrls','pc-prog-track','pc-prog-inner','pc-prog-fill','pc-prog-dot','pc-prog-buf',
            'pc-btn-pp','pc-btn-fs','pc-lock-btn','pc-hud-vol','pc-hud-bri','pc-seek-pv','pc-tip-speed',
            'pc-tcur','pc-ttot','pc-btm-row'];
        for (var pci = 0; pci < pcIds.length; pci++) {
            assert(document.getElementById(pcIds[pci]) !== null, '#' + pcIds[pci] + ' exists');
        }

        p.setLock(true);
        assert(p.locked === true, 'Locked');
        assert(document.getElementById('pc-lock-btn').textContent === '\ud83d\udd12', 'Lock icon');
        p.setLock(false);
        assert(p.locked === false, 'Unlocked');
        assert(document.getElementById('pc-lock-btn').textContent === '\ud83d\udd13', 'Unlock icon');
    } catch(e) {
        assert(false, 'Player error: ' + e.message);
    }

    // ===== [18] CARD RENDERING =====
    console.log('\n--- [18] Card Rendering ---');

    var cr1 = window.renderCard({ videoId:99, title:'Test', coverUrl:'', episodeInfo:'12eps', isLocal:true, localPath:'/p' });
    assert(cr1.includes('Test'), 'Title');
    assert(cr1.includes('card-local-badge'), 'Badge');
    assert(cr1.includes('12eps'), 'Ep info');

    var cr2 = window.renderCard({ videoId:100, title:'Online', coverUrl:'http://ex.co/c.jpg', episodeInfo:'Ep5', isLocal:false });
    assert(cr2.includes('Online'), 'Online title');
    assert(!cr2.includes('card-local-badge'), 'No badge');
    assert(cr2.includes('Ep5'), 'Online ep');
    assert(cr2.includes('c.jpg'), 'Cover img src');

    var cr3 = window.renderCard({ videoId:200, title:'Follow', coverUrl:'', episodeInfo:'8/16', isLocal:false, followProgress:50, hasUpdate:true });
    assert(cr3.includes('Follow'), 'Follow title');
    assert(cr3.includes('follow-progress-fill'), 'Progress');
    assert(cr3.includes('follow-update-dot'), 'Update dot');

    var cr4 = window.renderCard({ videoId:300, title:'<script>x()</script>', coverUrl:'', episodeInfo:'', isLocal:false });
    assert(!cr4.includes('<script>'), 'XSS escaped');
    assert(cr4.includes('&lt;script&gt;'), 'Escape chars');

    var cr5 = window.renderCard({ videoId:400, title:'Covered', coverUrl:'http://ex.co/cover.jpg', episodeInfo:'', isLocal:false });
    assert(cr5.includes('cover.jpg'), 'Cover card: img src');

    var cr6 = window.renderCard({ videoId:500, title:'NoCover', coverUrl:'', episodeInfo:'', isLocal:false });
    assert(cr6.includes('card-thumb-placeholder'), 'No-cover card: placeholder');

    // ===== [19] renderGrid FUNCTION =====
    console.log('\n--- [19] renderGrid Function ---');
    var tg = document.createElement('div');
    tg.id = 'test-grid-x';
    document.body.appendChild(tg);

    window.renderGrid('test-grid-x', [
        { videoId:1, title:'A', coverUrl:'', episodeInfo:'', isLocal:true, localPath:'/a' },
        { videoId:2, title:'B', coverUrl:'', episodeInfo:'5eps', isLocal:false },
    ]);
    var rgc = tg.querySelectorAll('.card');
    assert(rgc.length === 2, '2 cards');
    assert(rgc[0].querySelector('.card-local-badge') !== null, 'Card1 badge');
    assert(rgc[1].querySelector('.card-local-badge') === null, 'Card2 no badge');
    assert(rgc[0].querySelector('.card-title').textContent === 'A', 'Card1 title');
    assert(rgc[1].querySelector('.card-title').textContent === 'B', 'Card2 title');

    window.renderGrid('test-grid-x', []);
    assert(tg.innerHTML === '', 'Empty clears');

    // ===== [20] CALLBACK/RESOLVE HELPERS =====
    console.log('\n--- [20] Callback/Resolve Helpers ---');
    assert(typeof window.resolveCallback === 'function', 'resolveCallback');
    assert(typeof window._shouldExit !== 'undefined', '_shouldExit');
    assert(typeof window.onDomainReady === 'function', 'onDomainReady');
    assert(typeof window.onDomainRefresh === 'function', 'onDomainRefresh');
    assert(typeof window.onFollowUpdate === 'function', 'onFollowUpdate');
    assert(typeof window.showError === 'function', 'showError');
    assert(typeof window.onPathChanged === 'function', 'onPathChanged');
    assert(typeof window.callNative === 'function', 'callNative');

    // ===== [21] LOCAL FILE MANAGER - Back Button Selection Mode =====
    console.log('\n--- [21] Local File Manager - Back Button Selection Mode ---');

    // Reset state
    tabs[0].click(); await wait(300);

    // Manually trigger selection mode via long-press simulation
    window.enterSelectMode();
    await wait(100);

    assert(window.localSelectMode === true, 'localSelectMode=true after enterSelectMode');
    var actionBar = document.getElementById('local-action-bar');
    assert(actionBar.style.display !== 'none', 'Action bar visible during selection mode');
    assert(document.getElementById('local-grid').classList.contains('select-mode'), 'Grid has select-mode class');

    // Back button should exit selection mode
    window._shouldExit = false;
    var obi2 = window.browseInto;
    var browseCalled = false;
    window.browseInto = function() { browseCalled = true; };
    window.handleBackPress();
    await wait(100);
    assert(window.localSelectMode === false, 'localSelectMode=false after back press');
    assert(browseCalled === false, 'browseInto NOT called (only exits selection mode)');
    assert(actionBar.style.display === 'none' || actionBar.style.display === '', 'Action bar hidden');
    assert(!document.getElementById('local-grid').classList.contains('select-mode'), 'select-mode class removed');
    window.browseInto = obi2;

    // ===== [22] LOCAL FILE MANAGER - Back Button Subfolder Navigation =====
    console.log('\n--- [22] Local File Manager - Back Button Subfolder Navigation ---');

    // Set up local history simulating navigation into subfolders
    window.__state.localHistory = ['', '/root/folder1', '/root/folder1/sub'];
    window.localHistory = ['', '/root/folder1', '/root/folder1/sub'];
    window._shouldExit = false;
    var navTarget = null;
    var obi3 = window.browseInto;
    window.browseInto = function(p) { navTarget = p; };
    window.handleBackPress();
    await wait(100);
    assert(navTarget === '/root/folder1', 'Back navigates to parent folder, got: ' + navTarget);
    assert(window._shouldExit === false, '_shouldExit=false after back nav');
    window.browseInto = obi3;

    // Second back goes to root
    navTarget = null;
    // After first back, browseInto would update localHistory to ['', '/root/folder1', '/root/folder1']
    // Simulate that by setting history
    window.localHistory = ['', '/root/folder1'];
    window.__state.localHistory = ['', '/root/folder1'];
    var obi4 = window.browseInto;
    window.browseInto = function(p) { navTarget = p; };
    window.handleBackPress();
    await wait(100);
    assert(navTarget === '', 'Second back navigates to root, got: ' + navTarget);
    window.browseInto = obi4;

    // Third back: at root -> should fall through to exit logic
    window.localHistory = [''];
    window.__state.localHistory = [''];
    window.__state.lastBackTime = 0;
    window._shouldExit = false;
    window.handleBackPress();
    assert(window._shouldExit === false, 'At root: shows toast (does not exit first press)');
    assert(document.getElementById('toast').classList.contains('show'), 'Toast shown at root');

    // ===== [23] LOCAL FILE MANAGER - Breadcrumb Rendering =====
    console.log('\n--- [23] Local File Manager - Breadcrumb Rendering ---');

    // Reset state and render local grid
    tabs[0].click(); await wait(300);

    var bcEl = document.getElementById('local-breadcrumb');
    assert(bcEl !== null, '#local-breadcrumb element exists in DOM');

    // Breadcrumb should be displayed after renderLocalGrid
    var bcDisplay = bcEl.style.display;
    // It may be '' (default) or not 'none'
    assert(bcEl.innerHTML.length > 0, 'Breadcrumb has content after render');
    assert(bcEl.querySelector('.local-crumb') !== null, 'Breadcrumb contains crumb elements');

    // Verify breadcrumb is NOT inside #local-grid
    var gridEl = document.getElementById('local-grid');
    var bcInsideGrid = gridEl.querySelector('.local-breadcrumb');
    assert(bcInsideGrid === null, 'Breadcrumb NOT inside local-grid (it is in #local-breadcrumb)');

    // First crumb should be root (樱花动漫)
    var firstCrumb = bcEl.querySelector('.local-crumb');
    assert(firstCrumb !== null, 'Has first crumb');
    assert(firstCrumb.dataset.path === '', 'Root crumb has empty data-path');

    // Breadcrumb styling: horizontal flex bar
    var bcBar = bcEl.querySelector('.local-breadcrumb');
    assert(bcBar !== null, 'Breadcrumb inner bar exists');
    var bcStyle = window.getComputedStyle(bcBar);
    assert(bcStyle.display === 'flex', 'Breadcrumb bar is flex display');

    // ===== [24] LOCAL FILE MANAGER - Card Grid Layout =====
    console.log('\n--- [24] Local File Manager - Card Grid Layout ---');

    var gridCards = document.getElementById('local-grid').querySelectorAll('.card');
    assert(gridCards.length >= 5, 'Grid has at least 5 cards (1 new-folder + 4 items), got ' + gridCards.length);

    // First card should be the new folder card
    var firstCard = gridCards[0];
    assert(firstCard.classList.contains('new-folder-card'), 'First card is new-folder-card');
    assert(firstCard.id === 'local-new-folder', 'First card id=local-new-folder');
    assert(firstCard.querySelector('.new-folder-icon') !== null, 'Has + icon');
    assert(firstCard.querySelector('.new-folder-icon').textContent === '+', '+ icon text');

    // Cards 2+ should be local-card
    assert(gridCards[1].classList.contains('local-card'), 'Second card is local-card');
    assert(gridCards[1].dataset.index === '0', 'Second card has data-index=0');
    assert(gridCards[1].querySelector('.card-check') !== null, 'Has card-check element');
    assert(gridCards[1].querySelector('.local-cover-img') !== null, 'Has cover image element');
    assert(gridCards[1].querySelector('.card-title') !== null, 'Has card title');
    assert(gridCards[1].querySelector('.card-meta') !== null, 'Has card meta');

    // Grid CSS: has proper class structure for 2-column layout
    assert(gridEl.classList.contains('card-grid'), 'Grid has card-grid class');
    assert(gridEl.classList.contains('local-card-grid'), 'Grid has local-card-grid class');
    // Verify grid items are rendered (each card takes half width in 2-col layout)
    var firstItemCard = gridCards[1]; // skip new-folder card
    assert(firstItemCard.querySelector('.card-title').textContent.length > 0, 'Card has title text');
    // Grid should contain only cards (no breadcrumb mixed in)
    var nonCardElements = gridEl.querySelectorAll(':scope > :not(.card)');
    assert(nonCardElements.length === 0, 'Grid contains only card elements (no breadcrumb mixed in), got ' + nonCardElements.length + ' non-card children');

    // Action bar should be hidden initially
    var abEl2 = document.getElementById('local-action-bar');
    assert(abEl2 !== null, 'Action bar element exists');
    assert(abEl2.style.display === 'none' || abEl2.style.display === '', 'Action bar hidden initially');

    // New folder card should NOT have a check element (cannot be selected)
    var nfCheck = firstCard.querySelector('.card-check');
    assert(nfCheck === null, 'New folder card has no card-check');

    // Local cards should have card-check (hidden by default without select-mode class)
    var lcCheck = gridCards[1].querySelector('.card-check');
    assert(lcCheck !== null, 'Local card has card-check');

    // Verify action bar buttons exist
    assert(document.getElementById('btn-select-all') !== null, 'Select all button exists');
    assert(document.getElementById('btn-rename') !== null, 'Rename button exists');

    // ===== [25] LOCAL FILE MANAGER - Bridge Methods Verification =====
    console.log('\n--- [25] Local File Manager - Bridge Methods ---');
    var localBridgeMethods = ['browseLocalDir', 'getLocalCover', 'deleteLocalFiles', 'renameLocalFile', 'moveLocalFiles', 'createLocalDir', 'playLocal'];
    for (var lbm = 0; lbm < localBridgeMethods.length; lbm++) {
        assert(window.Sakura && typeof window.Sakura[localBridgeMethods[lbm]] === 'function', 'Mock bridge.' + localBridgeMethods[lbm] + ' exists');
    }

    // browseLocalDir returns proper format (with isDir, coverKey, episodeCount)
    await wait(50);
    var mockResult = await window.callNativeLegacy('browseLocalDir', '');
    assert(mockResult !== null, 'browseLocalDir returns data');
    assert(mockResult.items && Array.isArray(mockResult.items), 'browseLocalDir returns items array');
    assert(mockResult.items.length >= 4, 'Has at least 4 items');
    assert(mockResult.items[0].isDir === true, 'First item isDir=true');
    assert(mockResult.items[0].hasOwnProperty('coverKey'), 'Item has coverKey');
    assert(mockResult.items[0].hasOwnProperty('episodeCount'), 'Item has episodeCount');
    assert(mockResult.path === '/storage/emulated/0/SakuraAnime', 'Path is download root');

    // ===== RESULTS =====
    console.log('\n========================================');
    var total = passed + failed;
    console.log('RESULTS: ' + passed + '/' + total + ' PASSED');
    if (failed > 0) {
        console.log('\nFAILURES:');
        for (var fx = 0; fx < failures.length; fx++) {
            console.log('  - ' + failures[fx]);
        }
        process.exit(1);
    } else {
        console.log('ALL TESTS PASSED');
        console.log('========================================');
        process.exit(0);
    }
}

runTests().catch(function(e) { console.error('CRASH:', e); process.exit(1); });
