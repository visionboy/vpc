// ════════════════════════════════════════════════════════════
// Jumphost 이전 이력 탭
// ════════════════════════════════════════════════════════════

let migDetailContext = null;  // 현재 열린 상세 모달의 이전 이력 데이터

/** 이전 이력 상세 모달 뷰 모드 — 'summary' 또는 'json' */
let migDetailViewMode = 'summary';

/** 이전 이력 탭 — 현재 조회된 이력 목록 (롤백 확인 모달에서 재사용) */
let migHistoryPendingRollbackId = null;

/** 이력 완료 확인 모달 오픈 (진행중 → 완료) */
let migHistoryPendingCompleteId = null;

/**
 * 이전 이력 목록 조회 후 테이블 렌더링.
 *
 * @param {number} page - 0-based 페이지 번호
 */
async function loadMigrationHistory(page = 0) {
    const loading = document.getElementById('mig-history-loading');
    const empty   = document.getElementById('mig-history-empty');
    const tbody   = document.getElementById('mig-history-body');
    const table   = document.getElementById('mig-history-table');
    const pgn     = document.getElementById('mig-history-pagination');

    loading.classList.remove('d-none');
    table.classList.add('d-none');
    empty.classList.add('d-none');

    try {
        const data  = await apiFetch(`${MIG_API}?page=${page}&size=20`);
        const items = data.data?.content ?? [];
        const total = data.data?.totalElements ?? 0;
        const size  = data.data?.size ?? 20;

        loading.classList.add('d-none');

        if (items.length === 0) { empty.classList.remove('d-none'); return; }

        tbody.innerHTML = items.map(m => {
            const oldR = m.oldPeering;
            const newR = m.newPeering;
            const statusBadge = {
                IN_PROGRESS : '<span class="badge bg-warning text-dark">진행중</span>',
                COMPLETED   : '<span class="badge bg-success">완료</span>',
                ROLLED_BACK : '<span class="badge bg-secondary">롤백됨</span>'
            }[m.status] ?? m.status;

            // 진행중: 완료 + 롤백 버튼 / 완료됨·그 외: 버튼 없음
            let actionBtns = '';
            if (m.status === 'IN_PROGRESS') {
                actionBtns =
                    `<button class="btn btn-success btn-sm"
                             onclick="openHistoryCompleteModal(${m.id}, '${escHtml(oldR.requesterVpcId ?? '')}', '${escHtml(newR.requesterVpcId ?? '')}')">
                         &#10003; 완료
                     </button>
                     <button class="btn btn-outline-warning btn-sm"
                             onclick="openHistoryRollbackModal(${m.id}, '${escHtml(oldR.requesterVpcId ?? '')}', '${escHtml(newR.requesterVpcId ?? '')}')">
                         &#8617; 롤백
                     </button>`;
            }

            return `<tr>
                <td class="text-muted small">${m.id}</td>
                <td class="small">
                    <code>${escHtml(oldR.requesterVpcId ?? '-')}</code><br>
                    <span class="text-muted">${escHtml(oldR.requesterCidr ?? '')}</span>
                </td>
                <td class="small">
                    <code>${escHtml(newR.requesterVpcId ?? '-')}</code><br>
                    <span class="text-muted">${escHtml(newR.requesterCidr ?? '')}</span>
                </td>
                <td class="small">
                    <code>${escHtml(oldR.accepterVpcId ?? '-')}</code><br>
                    <span class="text-muted">${escHtml(oldR.accepterCidr ?? '')}</span>
                </td>
                <td>${statusBadge}</td>
                <td class="small text-muted">${fmtDatetime(m.createdAt)}</td>
                <td>
                    <div class="d-flex gap-1 flex-nowrap">
                        <button class="btn btn-outline-primary btn-sm"
                                onclick="openMigrationDetail(${m.id})">&#128203; 상세</button>
                        ${actionBtns}
                    </div>
                </td>
            </tr>`;
        }).join('');

        table.classList.remove('d-none');
        renderMigHistoryPagination(page, size, total, pgn);
    } catch (e) {
        loading.classList.add('d-none');
        showToast('이전 이력 조회 실패: ' + e.message, 'error');
    }
}

/** 이전 이력 페이지네이션 렌더링 */
function renderMigHistoryPagination(page, size, total, container) {
    const totalPages = Math.ceil(total / size);
    if (totalPages <= 1) { container.innerHTML = ''; return; }
    const buttons = [];
    for (let i = 0; i < totalPages; i++) {
        buttons.push(`<button class="btn btn-sm ${i === page ? 'btn-primary' : 'btn-outline-secondary'} mx-1"
                              onclick="loadMigrationHistory(${i})">${i + 1}</button>`);
    }
    container.innerHTML = buttons.join('');
}

/**
 * 요약/JSON 토글 전환.
 * 두 탭(AS-IS·TO-BE) 공통으로 적용된다.
 *
 * @param {'summary'|'json'} mode
 */
function setMigDetailView(mode) {
    migDetailViewMode = mode;
    document.getElementById('btn-mig-summary').className =
        'btn btn-' + (mode === 'summary' ? '' : 'outline-') + 'primary btn-sm';
    document.getElementById('btn-mig-json').className =
        'btn btn-' + (mode === 'json' ? '' : 'outline-') + 'primary btn-sm';
    ['asis', 'tobe'].forEach(side => {
        document.getElementById('mig-detail-' + side + '-summary').style.display =
            mode === 'summary' ? '' : 'none';
        document.getElementById('mig-detail-' + side + '-json').style.display =
            mode === 'json' ? '' : 'none';
    });
}

/**
 * 이전 이력 상세 모달 오픈.
 * AS-IS 스냅샷과 TO-BE 실시간 데이터를 동시에 로드하여 양쪽 탭에
 * diff 하이라이트가 적용된 요약·JSON 뷰를 렌더링한다.
 *
 * @param {number} migrationId
 */
async function openMigrationDetail(migrationId) {
    // 뷰 모드 초기화 (요약)
    migDetailViewMode = 'summary';
    document.getElementById('btn-mig-summary').className = 'btn btn-primary btn-sm';
    document.getElementById('btn-mig-json').className = 'btn btn-outline-primary btn-sm';
    ['asis', 'tobe'].forEach(side => {
        document.getElementById('mig-detail-' + side + '-summary').style.display = '';
        document.getElementById('mig-detail-' + side + '-json').style.display = 'none';
    });

    const modal = new bootstrap.Modal(document.getElementById('migDetailModal'));
    const LOADING = '<div class="text-muted">불러오는 중...</div>';
    ['mig-detail-asis-summary', 'mig-detail-asis-json',
     'mig-detail-tobe-summary', 'mig-detail-tobe-json'].forEach(id => {
        document.getElementById(id).innerHTML = LOADING;
    });
    migDetailContext = null;
    modal.show();

    try {
        const ctxRes = await apiFetch(`${MIG_API}/${migrationId}`);
        const m = ctxRes.data;
        migDetailContext = m;

        const oldR = m.oldPeering, newR = m.newPeering;
        const accepterRtbId = oldR.accepterRouteTableId;
        const accepterVpcId = oldR.accepterVpcId;

        // 스냅샷·TO-BE·VPC 이름 병렬 로드
        const [snapRes, tobeRtRes, tobeSgRes, oldReqNameRes, newReqNameRes, accNameRes] = await Promise.all([
            apiFetch(`/api/v1/peerings/${newR.id}/snapshots`),
            accepterRtbId
                ? apiFetch(`/api/v1/aws/route-table-detail?rtbId=${encodeURIComponent(accepterRtbId)}&account=accepter`)
                    .catch(() => ({ data: null }))
                : Promise.resolve({ data: null }),
            accepterVpcId
                ? apiFetch(`/api/v1/aws/vpc-security-groups?vpcId=${encodeURIComponent(accepterVpcId)}&account=accepter`)
                    .catch(() => ({ data: [] }))
                : Promise.resolve({ data: [] }),
            oldR.requesterVpcId
                ? apiFetch(`/api/v1/aws/vpc-name?vpcId=${encodeURIComponent(oldR.requesterVpcId)}&account=requester`)
                    .catch(() => ({ data: '' }))
                : Promise.resolve({ data: '' }),
            newR.requesterVpcId
                ? apiFetch(`/api/v1/aws/vpc-name?vpcId=${encodeURIComponent(newR.requesterVpcId)}&account=requester`)
                    .catch(() => ({ data: '' }))
                : Promise.resolve({ data: '' }),
            accepterVpcId
                ? apiFetch(`/api/v1/aws/vpc-name?vpcId=${encodeURIComponent(accepterVpcId)}&account=accepter`)
                    .catch(() => ({ data: '' }))
                : Promise.resolve({ data: '' })
        ]);

        // VPC 이름 포맷 헬퍼 — "vpc-xxx(name)" 또는 "vpc-xxx"
        const vpcLabel = (id, name) =>
            id ? escHtml(id) + (name ? `<span class="text-muted">(${escHtml(name)})</span>` : '') : '-';

        const oldReqName = oldReqNameRes.data ?? '';
        const newReqName = newReqNameRes.data ?? '';
        const accName    = accNameRes.data ?? '';
        const targetVpc  = accepterVpcId ?? '-';

        // 요약 카드 채우기 (VPC 이름 포함)
        document.getElementById('mig-detail-old-info').innerHTML =
            `<div><span class="text-muted">VPC:</span> <code>${vpcLabel(oldR.requesterVpcId, oldReqName)}</code></div>
             <div><span class="text-muted">CIDR:</span> <code>${escHtml(oldR.requesterCidr ?? '-')}</code></div>
             <div><span class="text-muted">Target VPC:</span> <code>${vpcLabel(targetVpc, accName)}</code></div>
             <div><span class="text-muted">Peering ID:</span> <code>${escHtml(oldR.peeringConnectionId ?? '-')}</code></div>
             <div><span class="text-muted">상태:</span> ${escHtml(oldR.status ?? '-')}</div>`;
        document.getElementById('mig-detail-new-info').innerHTML =
            `<div><span class="text-muted">VPC:</span> <code>${vpcLabel(newR.requesterVpcId, newReqName)}</code></div>
             <div><span class="text-muted">CIDR:</span> <code>${escHtml(newR.requesterCidr ?? '-')}</code></div>
             <div><span class="text-muted">Target VPC:</span> <code>${vpcLabel(targetVpc, accName)}</code></div>
             <div><span class="text-muted">Peering ID:</span> <code>${escHtml(newR.peeringConnectionId ?? '-')}</code></div>
             <div><span class="text-muted">상태:</span> ${escHtml(newR.status ?? '-')}</div>`;

        const snapshots = snapRes.data ?? [];

        // 수락자 RT 스냅샷 (accepterRtbId 일치 우선, 없으면 마지막 ROUTE_TABLE)
        const rtSnaps = snapshots.filter(s => s.dataType === 'ROUTE_TABLE');
        const rtSnap  = rtSnaps.find(s =>
            tryParseJson(s.snapshotData)?.routeTableId === accepterRtbId
        ) ?? rtSnaps[rtSnaps.length - 1] ?? null;

        const sgSnap  = snapshots.find(s => s.dataType === 'SECURITY_GROUP') ?? null;

        const asisRt = rtSnap ? tryParseJson(rtSnap.snapshotData) : null;
        const asisSg = sgSnap ? tryParseJson(sgSnap.snapshotData) : null;
        const tobeRt = tobeRtRes.data ?? null;
        const tobeSg = tobeSgRes.data ?? [];

        // AS-IS 탭 — TO-BE 데이터와 비교해 삭제될 항목 빨간색 강조
        renderMigDetailSection(
            document.getElementById('mig-detail-asis-summary'),
            asisRt, asisSg, tobeRt, tobeSg, 'removed'
        );
        renderSnapshotsJson(
            document.getElementById('mig-detail-asis-json'),
            [rtSnap, sgSnap].filter(Boolean)
        );

        // TO-BE 탭 — 신규 추가 항목 녹색, 완료 시 삭제될 구 PCX/CIDR 항목 핑크 강조
        const oldPcxId   = oldR.peeringConnectionId ?? null;
        const oldReqCidr = oldR.requesterCidr ?? null;
        renderMigDetailSection(
            document.getElementById('mig-detail-tobe-summary'),
            tobeRt, tobeSg, asisRt, asisSg, 'added',
            oldPcxId, oldReqCidr
        );
        let tobeJson = '';
        if (tobeRt) {
            tobeJson += `<div class="mb-3">
                <div class="fw-bold small text-secondary mb-1">ROUTE_TABLE</div>
                <pre class="snapshot-json">${escHtml(JSON.stringify(tobeRt, null, 2))}</pre>
            </div>`;
        }
        if (tobeSg && tobeSg.length > 0) {
            tobeJson += `<div class="mb-3">
                <div class="fw-bold small text-secondary mb-1">SECURITY_GROUP</div>
                <pre class="snapshot-json">${escHtml(JSON.stringify(tobeSg, null, 2))}</pre>
            </div>`;
        }
        document.getElementById('mig-detail-tobe-json').innerHTML =
            tobeJson || '<div class="text-muted">조회된 데이터가 없습니다.</div>';

    } catch (e) {
        const err = `<div class="text-danger">상세 조회 실패: ${escHtml(e.message)}</div>`;
        ['mig-detail-asis-summary', 'mig-detail-asis-json',
         'mig-detail-tobe-summary', 'mig-detail-tobe-json'].forEach(id => {
            document.getElementById(id).innerHTML = err;
        });
    }
}

/** 모달 오픈 시 데이터가 이미 로드되므로 탭 클릭 이벤트는 no-op */
async function loadMigDetailToBe() {}

// ─────────────────────────────────────────────────────────────────────────
// 변경 내역 비교 모달
// ─────────────────────────────────────────────────────────────────────────

/**
 * 이전 이력 상세 모달에서 "변경 내역 보기" 버튼 클릭 시 호출.
 * 수락자 라우팅 테이블과 보안 그룹의 이전(AS-IS 스냅샷)·이후(POST 스냅샷 또는 실시간) 상태를 나란히 표시한다.
 */
async function openMigChangeHistory() {
    if (!migDetailContext) return;

    const ctx            = migDetailContext;
    const newPeeringId   = ctx.newPeering?.id;
    const accepterRtbId  = ctx.oldPeering?.accepterRouteTableId;
    const accepterVpcId  = ctx.oldPeering?.accepterVpcId;
    const migId          = ctx.id ?? '';

    document.getElementById('mig-ch-id').textContent            = `#${migId}`;
    document.getElementById('mig-ch-rt-asis-rtbid').textContent = accepterRtbId ?? '-';
    document.getElementById('mig-ch-rt-tobe-rtbid').textContent = accepterRtbId ?? '-';
    document.getElementById('mig-ch-sg-asis-sgid').textContent  = accepterVpcId ?? '-';
    document.getElementById('mig-ch-sg-tobe-sgid').textContent  = accepterVpcId ?? '-';

    const LOADING = '<div class="text-muted py-2 px-3">불러오는 중...</div>';
    ['mig-ch-rt-asis','mig-ch-rt-tobe','mig-ch-sg-asis','mig-ch-sg-tobe']
        .forEach(id => { document.getElementById(id).innerHTML = LOADING; });

    const chModal = new bootstrap.Modal(document.getElementById('migChangeHistoryModal'));
    chModal.show();

    try {
        // 스냅샷 + 라우팅 테이블 현재 상태 병렬 조회
        const [snapRes, rtRes] = await Promise.all([
            apiFetch(`/api/v1/peerings/${newPeeringId}/snapshots`),
            accepterRtbId
                ? apiFetch(`/api/v1/aws/route-table-detail?rtbId=${encodeURIComponent(accepterRtbId)}&account=accepter`)
                    .catch(() => ({ data: null }))
                : Promise.resolve({ data: null })
        ]);

        const snapshots = snapRes.data ?? [];

        // ── 라우팅 테이블 ─────────────────────────────────────────────────
        const rtSnaps = snapshots.filter(s => s.dataType === 'ROUTE_TABLE');
        let asisRt = null;
        if (accepterRtbId) {
            asisRt = rtSnaps.find(s => {
                try { return tryParseJson(s.snapshotData)?.routeTableId === accepterRtbId; } catch { return false; }
            });
        }
        if (!asisRt && rtSnaps.length > 0) asisRt = rtSnaps[rtSnaps.length - 1];

        renderRtChange(
            asisRt ? tryParseJson(asisRt.snapshotData) : null,
            rtRes.data
        );

        // ── 보안 그룹 ────────────────────────────────────────────────────
        // AS-IS: SECURITY_GROUP 스냅샷 (배열 형식 신규 / 단일 객체 레거시)
        const sgPreSnap  = snapshots.find(s => s.dataType === 'SECURITY_GROUP')  ?? null;
        // TO-BE: SECURITY_GROUP_POST 스냅샷 우선 → 없으면 VPC 실시간 조회
        const sgPostSnap = snapshots.find(s => s.dataType === 'SECURITY_GROUP_POST') ?? null;

        const asisRawSg = sgPreSnap ? tryParseJson(sgPreSnap.snapshotData) : null;

        let tobeRawSg;
        if (sgPostSnap) {
            tobeRawSg = tryParseJson(sgPostSnap.snapshotData);
        } else if (accepterVpcId) {
            // POST 스냅샷이 없으면 실시간 VPC 전체 SG 조회로 폴백
            const fallbackRes = await apiFetch(
                `/api/v1/aws/vpc-security-groups?vpcId=${encodeURIComponent(accepterVpcId)}&account=accepter`
            ).catch(() => ({ data: [] }));
            tobeRawSg = fallbackRes.data ?? [];
        } else {
            tobeRawSg = null;
        }

        renderSgChange(asisRawSg, tobeRawSg);

    } catch (e) {
        const ERR = `<div class="text-danger px-3">조회 실패: ${escHtml(e.message)}</div>`;
        ['mig-ch-rt-asis','mig-ch-rt-tobe','mig-ch-sg-asis','mig-ch-sg-tobe']
            .forEach(id => { document.getElementById(id).innerHTML = ERR; });
    }
}

/**
 * 라우팅 테이블 변경 비교 렌더링.
 *
 * @param {object|null} asis  스냅샷 데이터 {routeTableId, vpcId, routes[]}
 * @param {object|null} tobe  실시간 데이터 {routeTableId, vpcId, routes[]}
 */
function renderRtChange(asis, tobe) {
    const asisRoutes = asis?.routes ?? [];
    const tobeRoutes = tobe?.routes ?? [];

    // 비교 키: destinationCidrBlock
    const asisKeys = new Set(asisRoutes.map(r => r.destinationCidrBlock));
    const tobeKeys = new Set(tobeRoutes.map(r => r.destinationCidrBlock));

    document.getElementById('mig-ch-rt-asis').innerHTML =
        asis ? renderRouteTable(asisRoutes, tobeKeys, 'removed') : '<div class="text-muted">스냅샷 없음</div>';
    document.getElementById('mig-ch-rt-tobe').innerHTML =
        tobe ? renderRouteTable(tobeRoutes, asisKeys, 'added')   : '<div class="text-muted">실시간 조회 실패</div>';
}

/**
 * 보안 그룹 변경 비교 렌더링.
 * 각 보안 그룹별로 헤더(ID·이름)를 표시하고 해당 그룹의 인바운드 규칙을 테이블로 렌더링한다.
 * diff 하이라이트는 동일 groupId 기준으로 반대쪽과 비교한다.
 *
 * 입력 데이터 형식:
 *  - 신규 배열 형식: [{groupId, groupName, inboundRules:[{protocol,fromPort,toPort,source}]}]
 *  - 레거시 단일 객체: {groupId, groupName, ingressRules:[{ipProtocol,fromPort,toPort,ipRanges:[]}]}
 *  - null: 데이터 없음
 *
 * @param {Array|object|null} asis  이전(AS-IS) 데이터
 * @param {Array|object|null} tobe  이후(TO-BE) 데이터
 */
function renderSgChange(asis, tobe) {
    const asisHasSg = asis !== null && asis !== undefined &&
                      (Array.isArray(asis) ? asis.length > 0 : !!asis.groupId);
    const tobeHasSg = tobe !== null && tobe !== undefined &&
                      (Array.isArray(tobe) ? tobe.length > 0 : !!tobe.groupId);

    if (!asisHasSg && !tobeHasSg) {
        const notice = '<div class="alert alert-info m-3 small mb-0">' +
            '보안 그룹 데이터가 없습니다. 이번 이전 이후 신규 이전을 진행하면 변경 전·후 데이터가 자동 저장됩니다.' +
            '</div>';
        document.getElementById('mig-ch-sg-asis').innerHTML = notice;
        document.getElementById('mig-ch-sg-tobe').innerHTML = notice;
        return;
    }

    const asisArr = asisHasSg ? normSgArray(asis) : [];
    const tobeArr = tobeHasSg ? normSgArray(tobe) : [];

    document.getElementById('mig-ch-sg-asis').innerHTML = asisHasSg
        ? renderSgGrouped(asisArr, tobeArr, 'removed')
        : '<div class="text-muted p-3">스냅샷 없음<br><small>이번 이전은 보안 그룹 스냅샷이 저장되지 않았습니다.</small></div>';

    document.getElementById('mig-ch-sg-tobe').innerHTML = tobeHasSg
        ? renderSgGrouped(tobeArr, asisArr, 'added')
        : '<div class="text-muted p-3">이후 데이터 없음</div>';
}

function openHistoryCompleteModal(migrationId, oldVpcShort, newVpcShort) {
    migHistoryPendingCompleteId = migrationId;
    document.getElementById('mig-history-complete-info').innerHTML =
        `이전 이력 ID <strong>#${migrationId}</strong>을 완료합니다.<br>
         기존 피어링(<code>${oldVpcShort}</code>)을 <strong>영구 삭제</strong>하고 신규 피어링(<code>${newVpcShort}</code>)을 유지합니다.`;
    new bootstrap.Modal(document.getElementById('migHistoryCompleteModal')).show();
}

/** 이력 완료 실행 */
async function executeHistoryComplete() {
    if (!migHistoryPendingCompleteId) return;
    bootstrap.Modal.getInstance(document.getElementById('migHistoryCompleteModal'))?.hide();

    try {
        await apiFetch(`${MIG_API}/${migHistoryPendingCompleteId}/complete`, {method: 'POST'});
        showToast('완료 처리되었습니다. 기존 피어링이 삭제되었습니다.', 'success');
        loadMigrationHistory();
        loadActive();
    } catch (e) {
        showToast('완료 처리 실패: ' + e.message, 'error');
    } finally {
        migHistoryPendingCompleteId = null;
    }
}

/** 이력 롤백 확인 모달 오픈 */
function openHistoryRollbackModal(migrationId, oldVpcShort, newVpcShort) {
    migHistoryPendingRollbackId = migrationId;
    document.getElementById('mig-history-rollback-info').innerHTML =
        `이전 이력 ID <strong>#${migrationId}</strong>을 롤백합니다.<br>
         신규 피어링(<code>${newVpcShort}</code>)을 삭제하고 기존 피어링(<code>${oldVpcShort}</code>)을 재생성합니다.`;
    new bootstrap.Modal(document.getElementById('migHistoryRollbackModal')).show();
}

/** 이력 롤백 실행 */
async function executeHistoryRollback() {
    if (!migHistoryPendingRollbackId) return;
    bootstrap.Modal.getInstance(document.getElementById('migHistoryRollbackModal'))?.hide();

    try {
        await apiFetch(`${MIG_API}/${migHistoryPendingRollbackId}/rollback`, {method: 'POST'});
        showToast('롤백이 완료되었습니다.', 'success');
        loadMigrationHistory();
    } catch (e) {
        showToast('롤백 실패: ' + e.message, 'error');
    } finally {
        migHistoryPendingRollbackId = null;
    }
}

// ── 중첩 모달 z-index 보정 ────────────────────────────────
// sgModal이 migAccepterResourceModal 위에서 열릴 때 뒤로 가려지는 버그 방지.
// Bootstrap 기본 z-index: backdrop=1050, modal=1055.
// 두 번째 모달은 backdrop=1060, modal=1065 로 올려야 앞에 표시됨.
(function () {
    const sgModalEl = document.getElementById('sgModal');

    sgModalEl.addEventListener('show.bs.modal', function () {
        if (document.querySelectorAll('.modal.show').length > 0) {
            this.style.zIndex = '1065';
        }
    });

    sgModalEl.addEventListener('shown.bs.modal', function () {
        const backdrops = document.querySelectorAll('.modal-backdrop');
        if (backdrops.length > 1) {
            backdrops[backdrops.length - 1].style.zIndex = '1060';
        }
    });

    sgModalEl.addEventListener('hidden.bs.modal', function () {
        this.style.zIndex = '';
    });
}());

// migChangeHistoryModal이 migDetailModal 위에서 열릴 때 뒤로 가려지는 버그 방지.
(function () {
    const chModalEl = document.getElementById('migChangeHistoryModal');

    chModalEl.addEventListener('show.bs.modal', function () {
        if (document.querySelectorAll('.modal.show').length > 0) {
            this.style.zIndex = '1065';
        }
    });

    chModalEl.addEventListener('shown.bs.modal', function () {
        const backdrops = document.querySelectorAll('.modal-backdrop');
        if (backdrops.length > 1) {
            backdrops[backdrops.length - 1].style.zIndex = '1060';
        }
    });

    chModalEl.addEventListener('hidden.bs.modal', function () {
        this.style.zIndex = '';
    });
}());
