// ════════════════════════════════════════════════════════════
// 활성 목록 / 전체 이력
// ════════════════════════════════════════════════════════════
async function loadActive() {
    const tbody = document.getElementById('active-tbody');
    tbody.innerHTML = '<tr><td colspan="8" class="text-center text-muted">불러오는 중...</td></tr>';
    try {
        const data = await apiFetch(API + '?size=50');
        const items = data.data?.content ?? [];
        tbody.innerHTML = items.length === 0
            ? '<tr><td colspan="8" class="text-center text-muted py-4">활성 피어링 없음</td></tr>'
            : items.map(p => `<tr>
                <td>${p.id}</td>
                <td><code class="small">${p.peeringConnectionId ?? '-'}</code></td>
                <td>${p.peeringName ?? '-'}</td>
                <td><code class="small">${p.requesterVpcId}</code><br>
                    <span class="text-muted small">${p.requesterCidr}</span></td>
                <td><code class="small">${p.accepterVpcId}</code><br>
                    <span class="text-muted small">${p.accepterCidr}</span></td>
                <td>${statusBadge(p.status)}</td>
                <td class="small">${fmt(p.createdAt)}</td>
                <td><button class="btn btn-danger btn-sm"
                            onclick="openDeleteModal(${p.id})">해제 및 삭제</button></td>
            </tr>`).join('');
    } catch (e) {
        tbody.innerHTML = `<tr><td colspan="8" class="text-center text-danger">${e.message}</td></tr>`;
    }
}

async function loadHistory() {
    const tbody = document.getElementById('history-tbody');
    tbody.innerHTML = '<tr><td colspan="9" class="text-center text-muted">불러오는 중...</td></tr>';
    try {
        const data = await apiFetch(API + '/history?size=50');
        const items = data.data?.content ?? [];
        tbody.innerHTML = items.length === 0
            ? '<tr><td colspan="9" class="text-center text-muted py-4">이력 없음</td></tr>'
            : items.map(p => `<tr>
                <td>${p.id}</td>
                <td><code class="small">${p.peeringConnectionId ?? '-'}</code></td>
                <td>${p.peeringName ?? '-'}</td>
                <td><code class="small">${p.requesterVpcId}</code><br>
                    <span class="text-muted small">${p.requesterCidr}</span></td>
                <td><code class="small">${p.accepterVpcId}</code><br>
                    <span class="text-muted small">${p.accepterCidr}</span></td>
                <td>${statusBadge(p.status)}</td>
                <td class="small">${fmt(p.createdAt)}</td>
                <td class="small">${fmt(p.deletedAt)}</td>
                <td>${p.status === 'DELETED'
                    ? `<button class="btn btn-outline-secondary btn-sm"
                               onclick="openSnapshotModal(${p.id})">스냅샷</button>` : '-'}</td>
            </tr>`).join('');
    } catch (e) {
        tbody.innerHTML = `<tr><td colspan="9" class="text-center text-danger">${e.message}</td></tr>`;
    }
}

// ════════════════════════════════════════════════════════════
// 삭제 모달
// ════════════════════════════════════════════════════════════
function openDeleteModal(id) {
    pendingDeleteId = id;
    new bootstrap.Modal(document.getElementById('deleteModal')).show();
}
document.getElementById('btn-confirm-delete').addEventListener('click', async () => {
    if (!pendingDeleteId) return;
    bootstrap.Modal.getInstance(document.getElementById('deleteModal')).hide();
    try {
        await apiFetch(`${API}/${pendingDeleteId}`, {method:'DELETE'});
        showToast('VPC Peering이 성공적으로 해제되었습니다.');
        loadActive();
    } catch (e) {
        showToast('삭제 실패: ' + e.message, 'error');
    } finally {
        pendingDeleteId = null;
    }
});
