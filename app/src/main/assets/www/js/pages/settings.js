// ==================== Settings ====================

async function loadSettings() {
    try {
        const data = await callNativeLegacy('getSettings');
        if (data) {
            $('#setting-path').textContent = data.downloadPath || '/storage/emulated/0/SakuraAnime';
            $('#setting-domain').textContent = data.activeDomain || '未检测到';
        }
    } catch(e) {}
}

function changeDownloadPath() {
    if (window.Sakura && window.Sakura.openDirectoryPicker) {
        window.Sakura.openDirectoryPicker();
    } else {
        showToast('请在系统文件管理器中选择目录', 2000);
    }
}

window.onPathChanged = function(path) {
    $('#setting-path').textContent = path;
    showToast('下载路径已更新: ' + path, 2000);
};
