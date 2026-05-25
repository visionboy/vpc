const API = '/api/v1/peerings';
const AWS = '/api/v1/aws';
const MIG_API = '/api/v1/migrations';
let pendingDeleteId = null;

// ── 공통 유틸 ──────────────────────────────────────────────
function showToast(msg, type = 'success') {
    const id = 'toast-' + Date.now();
    const bg = type === 'success' ? 'bg-success' : 'bg-danger';
    document.getElementById('toast-container').insertAdjacentHTML('beforeend', `
        <div id="${id}" class="toast text-white ${bg} border-0 mb-2" role="alert">
            <div class="d-flex">
                <div class="toast-body">${msg}</div>
                <button type="button" class="btn-close btn-close-white me-2 m-auto"
                        data-bs-dismiss="toast"></button>
            </div>
        </div>`);
    const el = document.getElementById(id);
    new bootstrap.Toast(el, {delay:5000}).show();
    el.addEventListener('hidden.bs.toast', () => el.remove());
}

function statusBadge(s) {
    const m = {ACTIVE:'success', PENDING:'warning', DELETED:'secondary', FAILED:'danger'};
    return `<span class="badge bg-${m[s]||'secondary'}">${s}</span>`;
}

function fmt(dt) { return dt ? new Date(dt).toLocaleString('ko-KR') : '-'; }

async function apiFetch(url, opts = {}) {
    const res = await fetch(url, {headers:{'Content-Type':'application/json'}, ...opts});
    const body = res.status === 204 ? null : await res.json();
    if (!res.ok) throw new Error(body?.message || '서버 오류');
    return body;
}

/** 간단한 날짜 포맷 */
function fmtDatetime(dt) {
    if (!dt) return '-';
    return String(dt).replace('T', ' ').substring(0, 16);
}

function escHtml(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

/** JSON 파싱 실패 시 null 반환 */
function tryParseJson(str) {
    try { return JSON.parse(str); } catch { return null; }
}
