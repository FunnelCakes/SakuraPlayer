// ==================== Search ====================

let searchResults = [];

async function doSearch() {
    const keyword = $('#search-input').value.trim();
    if (!keyword) { showToast('请输入搜索关键词'); return; }

    $('#search-results').innerHTML = '';
    $('#search-empty').style.display = 'none';
    $('#search-spinner').style.display = '';

    try {
        const data = await callNativeLegacy('search', keyword);
        searchResults = Array.isArray(data) ? data : [];
        if (searchResults.length) {
            $('#search-results').innerHTML = searchResults.map(r => renderCard(r)).join('');
            $('#search-empty').style.display = 'none';
        } else {
            $('#search-empty').style.display = '';
        }
    } catch(e) {
        handleApiError(e);
        $('#search-empty').style.display = '';
    }
    $('#search-spinner').style.display = 'none';
}
