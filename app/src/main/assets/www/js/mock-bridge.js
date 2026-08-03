// Mock JSBridge for browser testing
window.Sakura = {
    // Legacy alias
    browseDir: function(path, cb) {
        // redirect to browseLocalDir
        this.browseLocalDir(path, cb);
    },
    browseLocalDir: function(path, cb) {
        var effectivePath = path || '/storage/emulated/0/SakuraAnime';
        var parentPath = '';
        if (effectivePath !== '/storage/emulated/0/SakuraAnime') {
            var parts = effectivePath.split('/');
            parts.pop();
            parentPath = parts.join('/') || '/storage/emulated/0';
        }
        var mock = {
            path: effectivePath,
            name: effectivePath.split('/').pop() || '樱花动漫',
            parentPath: parentPath,
            items: [
                { name: '进击的巨人', path: effectivePath + '/进击的巨人', isDir: true, coverKey: 'v_1', episodeCount: 16, duration: '', size: '' },
                { name: '鬼灭之刃', path: effectivePath + '/鬼灭之刃', isDir: true, coverKey: 'v_2', episodeCount: 26, duration: '', size: '' },
                { name: '咒术回战', path: effectivePath + '/咒术回战', isDir: true, coverKey: '', episodeCount: 24, duration: '', size: '' },
                { name: '间谍过家家.mp4', path: effectivePath + '/间谍过家家.mp4', isDir: false, coverKey: '', episodeCount: 1, duration: '', size: '1.2 GB' },
            ]
        };
        setTimeout(function() { window[cb](null, JSON.stringify(mock)); }, 100);
    },
    getLocalCover: function(key, cb) {
        setTimeout(function() { window[cb](null, '""'); }, 50);
    },
    deleteLocalFiles: function(pathsJson, cb) {
        setTimeout(function() { window[cb](null, true); }, 100);
    },
    renameLocalFile: function(path, newName, cb) {
        setTimeout(function() { window[cb](null, true); }, 100);
    },
    moveLocalFiles: function(pathsJson, targetDir, cb) {
        setTimeout(function() { window[cb](null, true); }, 100);
    },
    createLocalDir: function(parentPath, name, cb) {
        setTimeout(function() { window[cb](null, true); }, 100);
    },
    playLocal: function(path) {},
    search: function(kw, cb) {
        const mock = [
            { videoId: 1, title: "进击的巨人 最终季", coverUrl: "", episodeInfo: "16集全", isLocal: true, localPath: "/storage/SakuraAnime/进击的巨人" },
            { videoId: 2, title: "进击的巨人 第三季", coverUrl: "", episodeInfo: "12集全", isLocal: false },
            { videoId: 3, title: "进击的巨人 OAD", coverUrl: "", episodeInfo: "8集", isLocal: false },
        ];
        setTimeout(() => window[cb](null, JSON.stringify(mock)), 500);
    },
    getDetail: function(vid, isLocal, path, cb) {
        const mock = {
            videoId: vid, title: "进击的巨人 最终季", coverUrl: "",
            description: "为了赢得真正的自由，艾伦选择启动地鸣，帕拉迪岛的命运即将迎来终结。",
            tags: ["热血", "战斗", "奇幻", "末日"],
            totalEps: 16, isLocal: !!isLocal,
            episodes: Array.from({length: 16}, (_, i) => ({ index: i+1, name: "第"+(i+1)+"集" }))
        };
        setTimeout(() => window[cb](null, JSON.stringify(mock)), 300);
    },
    getDownloadsPath: function() { return "/storage/emulated/0/SakuraAnime"; },
    getFollows: function(cb) {
        const mock = [
            { videoId: 1, title: "进击的巨人 最终季", coverUrl: "", watchedEps: 8, totalEps: 16, hasUpdate: true },
            { videoId: 2, title: "鬼灭之刃 无限列车篇", coverUrl: "", watchedEps: 7, totalEps: 7, hasUpdate: false },
        ];
        setTimeout(() => window[cb](null, JSON.stringify(mock)), 200);
    },
    getDownloadStatus: function(cb) {
        const mock = [
            { id: "1_1", videoId: 1, title: "进击的巨人", epIndex: 1, epName: "第1集", status: "downloading", progress: 45, speed: "3.2MB/s", eta: "2m", error: "" },
            { id: "1_2", videoId: 1, title: "进击的巨人", epIndex: 2, epName: "第2集", status: "queued", progress: 0, speed: "", eta: "", error: "" },
        ];
        setTimeout(() => window[cb](null, JSON.stringify(mock)), 200);
    },
    getDiscover: function(cat, page, cb) {
        const titleByCat = {
            'recommend': ["正后方的神威", "魔法少女奈叶 2026", "关于我转生变成史莱姆这档事 第四季"],
            '20': ["咒术回战", "鬼灭之刃", "进击的巨人 最终季"],
            '21': ["斗破苍穹", "完美世界", "仙逆"],
            '22': ["咒术回战 剧场版", "间谍过家家", "鬼灭之刃 无限列车篇"],
            '23': ["你的名字。", "天气之子", "铃芽之旅"]
        };
        const titles = titleByCat[cat] || titleByCat['recommend'];
        const mock = titles.map((t, i) => ({
            videoId: 10 + i, title: t, coverUrl: "", episodeInfo: "更新至第1集", isLocal: false
        }));
        setTimeout(() => window[cb](null, JSON.stringify(mock)), 300);
    },
    getSettings: function(cb) {
        setTimeout(() => window[cb](null, JSON.stringify({ downloadPath: "/storage/emulated/0/SakuraAnime", activeDomain: "yinghua14.com" })), 100);
    },
    // No-op for write operations
    addDownload: function() {}, addBatchDownload: function() {}, pauseDownload: function() {},
    resumeDownload: function() {}, cancelDownload: function() {},
    addFollow: function() {}, removeFollow: function() {}, markWatched: function() {},
    setDownloadPath: function() {}, refreshDomain: function() {}, checkFollowUpdates: function() {},
    getDownloadRecord: function(path, cb) {
        if (path.indexOf('进击的巨人') !== -1) {
            setTimeout(function() { window[cb](null, JSON.stringify({
                videoId: 1, title: '进击的巨人', epIndex: 1,
                localPath: path, coverUrl: ''
            })); }, 50);
        } else {
            setTimeout(function() { window[cb](null, 'null'); }, 50);
        }
    },
    redownloadLocal: function(pathsJson, cb) {
        var paths = JSON.parse(pathsJson);
        setTimeout(function() { window[cb](null, paths.length); }, 200);
    },
    playOnline: function(videoId, title, epIndex, cb) {
        setTimeout(function() { window[cb](null, JSON.stringify({m3u8Url: ''})); }, 200);
    },
    openFullscreen: function(videoId, title, epIndex, cb) {
        setTimeout(function() { window[cb](null, JSON.stringify({})); }, 200);
    },
    syncDownloadRecords: function(cb) {
        setTimeout(function() { window[cb](null, 0); }, 100);
    },
    resetAndResyncRecords: function(cb) {
        setTimeout(function() { window[cb](null, 0); }, 100);
    },
    getDownloadedEps: function(videoId, cb) {
        setTimeout(function() { window[cb](null, JSON.stringify([])); }, 50);
    },
    openDirectoryPicker: function() {},

    getLocalVideoUrl: function(path, cb) {
        setTimeout(function() { window[cb](null, 'file://' + path); }, 50);
    },
    playLocalFromUrl: function(contentUrl, title, epIndex) {},
};
// Defer onDomainReady call until app.js is loaded
setTimeout(() => {
    if (window.onDomainReady) window.onDomainReady("yinghua14.com");
}, 100);
// Override callNativeLegacy to use mock bridge directly
const origCallNativeLegacy = window.callNativeLegacy;
window.callNativeLegacy = function(fn, ...args) {
    return new Promise((resolve, reject) => {
        const cid = 'cb_mock_' + (++window._mockCid || 1);
        window._mockCid = (window._mockCid || 0) + 1;
        window['_legacy_' + cid] = function(err, data) {
            delete window['_legacy_' + cid];
            if (err) reject(new Error(err));
            else resolve(typeof data === 'string' ? JSON.parse(data) : data);
        };
        if (window.Sakura && window.Sakura[fn]) {
            window.Sakura[fn](...args, '_legacy_' + cid);
        }
    });
};
