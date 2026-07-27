// ==================== Follow List ====================

async function loadFollowList() {
    try {
        const data = await callNativeLegacy('getFollows');
        const items = (data || []).map(f => ({
            videoId: f.videoId,
            title: f.title,
            coverUrl: f.coverUrl,
            episodeInfo: `${f.watchedEps || 0} / ${f.totalEps || 0} 集`,
            followProgress: f.totalEps ? Math.round((f.watchedEps || 0) / f.totalEps * 100) : 0,
            hasUpdate: f.hasUpdate,
            isLocal: false
        }));
        renderGrid('follow-grid', items);
        $('#follow-empty').style.display = items.length ? 'none' : '';
    } catch(e) {
        $('#follow-grid').innerHTML = '';
        $('#follow-empty').style.display = '';
    }
}
