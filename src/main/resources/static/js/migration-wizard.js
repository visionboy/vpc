// ════════════════════════════════════════════════════════════
// Jumphost 이전 마법사 — 2단계 상태 머신
// ════════════════════════════════════════════════════════════
const migS = {
    existing: null,     // 선택된 기존 피어링 전체 데이터
    startResult: null   // POST /migrate/start 응답 (migrationId, newPeering, existingPeeringId)
};

/** loadMigrateSource()에서 조회한 VPC Name 캐시 — onMigrateSourceChange()에서 배지 업데이트에 사용 */
let migVpcNames = {};

/** 단계 패널 전환 + 진행 표시기 업데이트 */
function migGoStep(n) {
    [1,2,3,4].forEach(i => {
        document.getElementById('mig-panel-' + i).classList.toggle('d-none', i !== n);
    });
    document.getElementById('mig-final-result').classList.add('d-none');

    const pct = {1:25, 2:50, 3:75, 4:100};
    document.getElementById('mig-progress-bar').style.width = (pct[n] ?? 25) + '%';

    ['1','2','3','4'].forEach(i => {
        const el = document.getElementById('mig-label-' + i);
        const active = parseInt(i) <= n;
        el.style.color = active ? 'var(--bs-primary)' : '';
        el.classList.toggle('fw-bold', active);
        el.classList.toggle('text-muted', !active);
    });
}

async function loadMigrateSource() {
    const sel = document.getElementById('migrate-source-select');
    sel.innerHTML = '<option value="">불러오는 중...</option>';
    sel.disabled = true;
    try {
        const data = await apiFetch(API + '?size=100');
        const items = data.data?.content ?? [];

        // 고유 VPC ID 수집 — requester/accepter 계정 분리
        const requesterIds = [...new Set(items.map(p => p.requesterVpcId).filter(Boolean))];
        const accepterIds  = [...new Set(items.map(p => p.accepterVpcId).filter(Boolean))];

        // VPC Name 병렬 조회
        const AWS_API = '/api/v1/aws/vpc-name';
        const fetchName = (vpcId, account) =>
            apiFetch(`${AWS_API}?vpcId=${encodeURIComponent(vpcId)}&account=${account}`)
                .then(r => ({ vpcId, name: r.data ?? '' }))
                .catch(() => ({ vpcId, name: '' }));

        const nameResults = await Promise.allSettled([
            ...requesterIds.map(id => fetchName(id, 'requester')),
            ...accepterIds.map(id  => fetchName(id, 'accepter'))
        ]);

        const vpcNames = {};
        nameResults.forEach(r => {
            if (r.status === 'fulfilled' && r.value.name) {
                vpcNames[r.value.vpcId] = r.value.name;
            }
        });
        migVpcNames = vpcNames;  // 모듈 변수에 저장 → onMigrateSourceChange()에서 배지에 사용

        const fmtVpc = id => id ? (vpcNames[id] ? `${id}(${vpcNames[id]})` : id) : '';

        sel.innerHTML = '<option value="">— 활성 피어링 선택 —</option>' +
            items.map(p => {
                const parts = [];
                if (p.peeringName) parts.push(`[${p.peeringName}]`);
                parts.push(`${fmtVpc(p.requesterVpcId)} → ${fmtVpc(p.accepterVpcId)}`);
                return `<option value="${p.id}">${parts.join(' ')}</option>`;
            }).join('');
        sel._items = items;
        sel.disabled = false;
    } catch (e) {
        sel.innerHTML = '<option value="">불러오기 실패</option>';
        sel.disabled = false;
        showToast('피어링 목록 불러오기 실패: ' + e.message, 'error');
    }
}

function onMigrateSourceChange() {
    const sel   = document.getElementById('migrate-source-select');
    const id    = parseInt(sel.value);
    const items = sel._items ?? [];

    document.getElementById('migrate-existing-info').classList.add('d-none');
    migS.existing = null;

    if (!id) return;

    migS.existing = items.find(p => p.id === id);
    if (!migS.existing) return;

    // 기존 피어링 정보 카드 채우기
    document.getElementById('mig-old-name').textContent     = migS.existing.peeringName ?? '';
    document.getElementById('mig-old-req-vpc').textContent  = migS.existing.requesterVpcId;
    document.getElementById('mig-old-req-cidr').textContent = migS.existing.requesterCidr;
    document.getElementById('mig-old-pcx').textContent      = migS.existing.peeringConnectionId ?? '-';
    document.getElementById('mig-old-acc-vpc').textContent  = migS.existing.accepterVpcId;
    document.getElementById('mig-old-acc-cidr').textContent = migS.existing.accepterCidr;
    document.getElementById('mig-old-acc-rt').textContent   = migS.existing.accepterRouteTableId ?? '-';
    // VPC 이름 배지 업데이트 (migVpcNames는 loadMigrateSource()에서 채워진 캐시)
    const reqName = migVpcNames[migS.existing.requesterVpcId];
    const accName = migVpcNames[migS.existing.accepterVpcId];
    document.getElementById('mig-badge-requester').textContent =
        '교체 대상' + (reqName ? ` (${reqName})` : '');
    document.getElementById('mig-badge-accepter').textContent =
        '수락자' + (accName ? ` (${accName})` : '');

    document.getElementById('migrate-existing-info').classList.remove('d-none');

    // STEP 2 입력 필드 초기화
    document.getElementById('mig-new-vpc-id').value = '';
    document.getElementById('mig-new-cidr').value   = '';
    document.getElementById('mig-new-rt-id').innerHTML = '<option value="">VPC ID 입력 후 [조회] 클릭</option>';
    document.getElementById('mig-rt-status').textContent = '';
    document.getElementById('btn-mig-step2-next').disabled = true;
    migCidrCheck();
}

/**
 * STEP 2 입력값 실시간 검증.
 * - VPC ID 중복(교체 대상·수락자와 동일) 경고
 * - CIDR 겹침(교체 대상·수락자 양쪽 모두) 경고
 * - 경고가 있으면 다음 버튼 비활성화
 */
function migCidrCheck() {
    if (!migS.existing) return;
    const newVpc  = document.getElementById('mig-new-vpc-id').value.trim();
    const newCidr = document.getElementById('mig-new-cidr').value.trim();
    const newRt   = document.getElementById('mig-new-rt-id').value;
    const reqVpc  = migS.existing.requesterVpcId;
    const accVpc  = migS.existing.accepterVpcId;
    const reqCidr = migS.existing.requesterCidr;
    const accCidr = migS.existing.accepterCidr;

    // VPC ID 중복 체크
    const vpcWarnEl = document.getElementById('mig-vpc-warn');
    let vpcMsg = '';
    if (newVpc && newVpc === reqVpc) {
        vpcMsg = '&#9888;&#65039; 교체 대상(VPC-A)과 동일한 VPC ID입니다. VPC Peering이 불가합니다.';
    } else if (newVpc && newVpc === accVpc) {
        vpcMsg = '&#9888;&#65039; 수락자(VPC-B)와 동일한 VPC ID입니다. VPC Peering이 불가합니다.';
    }
    vpcWarnEl.innerHTML = vpcMsg;
    vpcWarnEl.classList.toggle('d-none', !vpcMsg);

    // CIDR 겹침 체크 (교체 대상·수락자 양쪽)
    const cidrWarnEl = document.getElementById('mig-cidr-warn');
    let cidrMsg = '';
    if (CIDR_RE.test(newCidr)) {
        if (isCidrOverlapping(newCidr, accCidr)) {
            cidrMsg = `&#9888;&#65039; 수락자(VPC-B) CIDR <code>${accCidr}</code>과 겹칩니다. VPC Peering이 불가합니다.`;
        } else if (isCidrOverlapping(newCidr, reqCidr)) {
            cidrMsg = `&#9888;&#65039; 교체 대상(VPC-A) CIDR <code>${reqCidr}</code>과 겹칩니다. VPC Peering이 불가합니다.`;
        }
    }
    cidrWarnEl.innerHTML = cidrMsg;
    cidrWarnEl.classList.toggle('d-none', !cidrMsg);

    const hasError  = !!(vpcMsg || cidrMsg);
    const allFilled = newVpc && CIDR_RE.test(newCidr) && newRt;

    // 에러가 있거나 필수 항목 미입력 시 버튼 비활성화
    document.getElementById('btn-mig-step2-next').disabled = !allFilled || hasError;

    // STEP 3 미리보기 데이터 갱신
    if (allFilled) {
        document.getElementById('prev-new-vpc').textContent      = newVpc;
        document.getElementById('prev-new-cidr').textContent     = newCidr;
        document.getElementById('prev-acc-vpc').textContent      = migS.existing.accepterVpcId;
        document.getElementById('prev-acc-cidr').textContent     = migS.existing.accepterCidr;
        document.getElementById('prev-old-req-vpc').textContent  = migS.existing.requesterVpcId;
        document.getElementById('prev-old-req-cidr').textContent = migS.existing.requesterCidr;
        document.getElementById('prev-acc-vpc2').textContent     = migS.existing.accepterVpcId;
        document.getElementById('prev-acc-cidr2').textContent    = migS.existing.accepterCidr;

        // VPC 이름 스팬 업데이트 — migVpcNames 캐시 기준 (신규 VPC-C는 미캐시일 수 있음)
        const accName = migVpcNames[migS.existing.accepterVpcId];
        const reqName = migVpcNames[migS.existing.requesterVpcId];
        const newName = migVpcNames[newVpc];
        document.getElementById('prev-new-vpc-name').textContent     = newName ? `(${newName})` : '';
        document.getElementById('prev-acc-vpc-name').textContent     = accName ? `(${accName})` : '';
        document.getElementById('prev-old-req-vpc-name').textContent = reqName ? `(${reqName})` : '';
        document.getElementById('prev-acc-vpc2-name').textContent    = accName ? `(${accName})` : '';
    }
}

/**
 * "미리보기 확인 →" 클릭 핸들러.
 * 유효성 오류가 있으면 alert 후 진행 차단, 정상이면 STEP 3으로 이동.
 */
function migStep2Next() {
    if (!migS.existing) return;
    const newVpc  = document.getElementById('mig-new-vpc-id').value.trim();
    const newCidr = document.getElementById('mig-new-cidr').value.trim();
    const reqVpc  = migS.existing.requesterVpcId;
    const accVpc  = migS.existing.accepterVpcId;
    const reqCidr = migS.existing.requesterCidr;
    const accCidr = migS.existing.accepterCidr;

    const errors = [];

    if (newVpc === reqVpc) {
        errors.push(`입력한 VPC ID(${newVpc})가 교체 대상(VPC-A)과 동일합니다.`);
    }
    if (newVpc === accVpc) {
        errors.push(`입력한 VPC ID(${newVpc})가 수락자(VPC-B)와 동일합니다.`);
    }
    if (CIDR_RE.test(newCidr) && isCidrOverlapping(newCidr, accCidr)) {
        errors.push(`신규 CIDR(${newCidr})이 수락자(VPC-B) CIDR(${accCidr})과 겹칩니다.`);
    }
    if (CIDR_RE.test(newCidr) && isCidrOverlapping(newCidr, reqCidr)) {
        errors.push(`신규 CIDR(${newCidr})이 교체 대상(VPC-A) CIDR(${reqCidr})과 겹칩니다.`);
    }

    if (errors.length > 0) {
        alert('⚠️ VPC Peering 생성이 불가합니다.\n\n' +
              errors.map((e, i) => `${i + 1}. ${e}`).join('\n') +
              '\n\n입력값을 확인해주세요.');
        return;
    }
    migGoStep(3);
}

async function loadMigNewRouteTables() {
    const vpcId = document.getElementById('mig-new-vpc-id').value.trim();
    if (!vpcId) { showToast('VPC ID를 입력해주세요.', 'error'); return; }
    if (!vpcId.startsWith('vpc-')) {
        showToast('올바른 VPC ID 형식이 아닙니다. (예: vpc-xxxxxxxxxxxxxxxxx)', 'error'); return;
    }

    const sel    = document.getElementById('mig-new-rt-id');
    const status = document.getElementById('mig-rt-status');
    const cidrEl = document.getElementById('mig-new-cidr');
    sel.disabled = true;
    sel.innerHTML = '<option>조회 중...</option>';
    status.textContent = '조회 중...';

    try {
        const [cidrRes, rtRes, nameRes] = await Promise.allSettled([
            apiFetch(`${AWS}/vpc-cidr?vpcId=${encodeURIComponent(vpcId)}&account=requester`),
            apiFetch(`${AWS}/route-tables?vpcId=${encodeURIComponent(vpcId)}&account=requester`),
            apiFetch(`${AWS}/vpc-name?vpcId=${encodeURIComponent(vpcId)}&account=requester`)
        ]);

        // VPC 이름 캐시에 저장 — STEP 3 미리보기에서 바로 사용
        if (nameRes.status === 'fulfilled' && nameRes.value.data) {
            migVpcNames[vpcId] = nameRes.value.data;
        }

        // CIDR 자동 입력 — 조회마다 항상 덮어씀
        if (cidrRes.status === 'fulfilled') {
            const cidr = cidrRes.value.data;
            if (cidr) {
                cidrEl.value = cidr;
                cidrEl.classList.add('border-success');
                setTimeout(() => cidrEl.classList.remove('border-success'), 2000);
            }
        } else {
            showToast('CIDR 자동 조회 실패 (직접 입력해주세요): ' + cidrRes.reason?.message, 'error');
        }

        // 라우팅 테이블 드롭다운 채우기
        if (rtRes.status === 'fulfilled') {
            const tables = rtRes.value.data ?? [];
            if (tables.length === 0) {
                sel.innerHTML = '<option value="">라우팅 테이블 없음</option>';
                status.textContent = '(결과 없음)';
            } else {
                sel.innerHTML = '<option value="">선택하세요</option>' +
                    tables.map(rt => {
                        const label = [
                            rt.main ? '★ Main' : '',
                            rt.name ? `[${rt.name}]` : '',
                            rt.subnetIds?.length ? `서브넷 ${rt.subnetIds.length}개` : '서브넷 없음'
                        ].filter(Boolean).join(' · ');
                        return `<option value="${rt.routeTableId}">${rt.routeTableId}  ${label}</option>`;
                    }).join('');
                const main = tables.find(rt => rt.main);
                if (main) sel.value = main.routeTableId;
                status.textContent = `(${tables.length}개)`;
            }
        } else {
            sel.innerHTML = '<option value="">조회 실패</option>';
            showToast('라우팅 테이블 조회 실패: ' + rtRes.reason?.message, 'error');
        }

        migCidrCheck();
    } catch (e) {
        sel.innerHTML = '<option value="">조회 실패</option>';
        showToast('조회 실패: ' + e.message, 'error');
    } finally {
        sel.disabled = false;
    }
}

/** STEP 3 → POST /migrate/start → STEP 4 */
async function executeMigrateStart() {
    if (!migS.existing) return;

    const newVpcId = document.getElementById('mig-new-vpc-id').value.trim();
    const newCidr  = document.getElementById('mig-new-cidr').value.trim();
    const newRtId  = document.getElementById('mig-new-rt-id').value;
    const newAccId = document.getElementById('mig-new-account-id').value.trim();

    if (!newVpcId || !newCidr || !newRtId) {
        showToast('신규 Jumphost의 VPC ID, CIDR, 라우팅 테이블을 모두 입력해주세요.', 'error'); return;
    }
    if (isCidrOverlapping(newCidr, migS.existing.accepterCidr)) {
        showToast(`CIDR 겹침: ${newCidr} ↔ ${migS.existing.accepterCidr}`, 'error'); return;
    }

    const btn = document.getElementById('btn-migrate-start');
    btn.disabled = true;
    btn.textContent = '처리 중...';

    const payload = {
        newRequesterVpcId:        newVpcId,
        newRequesterCidr:         newCidr,
        newRequesterRouteTableId: newRtId,
    };
    if (newAccId) payload.newRequesterAccountId = newAccId;

    try {
        const res = await apiFetch(`${API}/${migS.existing.id}/migrate/start`,
            {method:'POST', body:JSON.stringify(payload)});
        migS.startResult = res.data;

        // STEP 4 카드 채우기
        const np = migS.startResult.newPeering;
        document.getElementById('mig-start-result-msg').innerHTML =
            `신규 Peering ID: <code>${np.peeringConnectionId ?? '-'}</code> (DB ID: <strong>${np.id}</strong>)<br>` +
            `라우팅 테이블·보안 그룹 설정 완료. 기존 피어링 DB ID: <strong>${migS.startResult.existingPeeringId}</strong>`;

        // VPC ID 뒤에 이름을 괄호로 붙이는 헬퍼
        const vpcLabel = id => { const n = migVpcNames[id]; return n ? `${id}(${n})` : id; };

        document.getElementById('mig-new-peering-card').innerHTML =
            `<div>Peering ID: <code>${np.peeringConnectionId ?? '-'}</code></div>` +
            `<div>DB ID: <strong>${np.id}</strong></div>` +
            `<div>VPC: <code>${vpcLabel(np.requesterVpcId)}</code> ↔ <code>${vpcLabel(np.accepterVpcId)}</code></div>`;

        document.getElementById('mig-old-peering-card').innerHTML =
            `<div>Peering ID: <code>${migS.existing.peeringConnectionId ?? '-'}</code></div>` +
            `<div>DB ID: <strong>${migS.startResult.existingPeeringId}</strong></div>` +
            `<div>VPC: <code>${vpcLabel(migS.existing.requesterVpcId)}</code> ↔ <code>${vpcLabel(migS.existing.accepterVpcId)}</code></div>`;

        showToast('신규 피어링 생성 완료! 연결을 확인하고 완료 또는 롤백을 선택하세요.');
        migGoStep(4);
    } catch (e) {
        showToast('신규 피어링 생성 실패: ' + e.message, 'error');
    } finally {
        btn.disabled = false;
        btn.textContent = '실행';
    }
}

function openMigCompleteModal() {
    new bootstrap.Modal(document.getElementById('migCompleteModal')).show();
}

function openMigRollbackModal() {
    new bootstrap.Modal(document.getElementById('migRollbackModal')).show();
}

/** 완료: POST /api/v1/migrations/{migrationId}/complete */
async function executeMigrateComplete() {
    if (!migS.startResult) return;
    bootstrap.Modal.getInstance(document.getElementById('migCompleteModal'))?.hide();

    try {
        await apiFetch(`/api/v1/migrations/${migS.startResult.migrationId}/complete`, {method:'POST'});
        showFinalResult(
            true,
            '이전 완료 — 기존 피어링이 삭제되었습니다.',
            `삭제된 기존 Peering DB ID: <strong>${migS.startResult.existingPeeringId}</strong><br>` +
            `신규 Peering: <code>${migS.startResult.newPeering?.peeringConnectionId ?? '-'}</code> (DB ID: <strong>${migS.startResult.newPeering?.id}</strong>)`,
            true
        );
        loadActive();
    } catch (e) {
        showToast('기존 피어링 삭제 실패: ' + e.message, 'error');
    }
}

/** 롤백: POST /api/v1/migrations/{migrationId}/rollback */
async function executeMigrateRollback() {
    if (!migS.startResult) return;
    bootstrap.Modal.getInstance(document.getElementById('migRollbackModal'))?.hide();

    try {
        await apiFetch(`/api/v1/migrations/${migS.startResult.migrationId}/rollback`, {method:'POST'});
        showFinalResult(
            false,
            '롤백 완료 — 신규 피어링이 삭제되었습니다.',
            `삭제된 신규 Peering DB ID: <strong>${migS.startResult.newPeering?.id}</strong><br>` +
            `기존 피어링(DB ID: <strong>${migS.startResult.existingPeeringId}</strong>)은 계속 유지됩니다.`,
            false
        );
        loadActive();
    } catch (e) {
        showToast('신규 피어링 삭제(롤백) 실패: ' + e.message, 'error');
    }
}

function showFinalResult(isComplete, title, msg, showSnapshotGuide) {
    [1,2,3,4].forEach(i => document.getElementById('mig-panel-' + i).classList.add('d-none'));
    const final = document.getElementById('mig-final-result');
    final.classList.remove('d-none');

    const alert = document.getElementById('mig-final-alert');
    alert.className = 'alert mb-3 ' + (isComplete ? 'alert-success' : 'alert-secondary');
    document.getElementById('mig-final-title').textContent = title;
    document.getElementById('mig-final-msg').innerHTML = msg;

    const guide = document.getElementById('mig-snapshot-guide');
    guide.style.display = showSnapshotGuide ? '' : 'none';

    document.getElementById('mig-progress-bar').style.width = '100%';
}

function resetMigration() {
    migS.existing    = null;
    migS.startResult = null;

    document.getElementById('migrate-source-select').value = '';
    document.getElementById('migrate-existing-info').classList.add('d-none');
    document.getElementById('mig-new-vpc-id').value    = '';
    document.getElementById('mig-new-cidr').value      = '';
    document.getElementById('mig-new-account-id').value = '';
    document.getElementById('mig-new-rt-id').innerHTML = '<option value="">VPC ID 입력 후 [조회] 클릭</option>';
    document.getElementById('mig-rt-status').textContent = '';
    document.getElementById('btn-mig-step2-next').disabled = true;
    document.getElementById('mig-final-result').classList.add('d-none');
    migGoStep(1);
    loadMigrateSource();
}

// ════════════════════════════════════════════════════════════
// Jumphost 이전 — 수락자(VPC-B) 연결 자원 조회 모달
// ════════════════════════════════════════════════════════════
async function openMigAccepterResources() {
    if (!migS.existing) return;
    const vpcId   = migS.existing.accepterVpcId;
    const account = 'accepter';

    document.getElementById('mig-acc-res-vpc-id').textContent = vpcId;

    // 탭 초기화 — 로딩 상태로
    const loadingHtml = (cols) => `<tr><td colspan="${cols}" class="text-muted text-center py-3">
        <span class="spinner-border spinner-border-sm me-1"></span>불러오는 중...</td></tr>`;
    document.getElementById('mig-acc-ec2-tbody').innerHTML = loadingHtml(6);
    document.getElementById('mig-acc-rds-tbody').innerHTML = loadingHtml(6);
    document.getElementById('mig-acc-rt-content').innerHTML =
        '<p class="text-muted text-center py-3"><span class="spinner-border spinner-border-sm me-1"></span>불러오는 중...</p>';
    document.getElementById('mig-acc-sg-content').innerHTML =
        '<p class="text-muted text-center py-3"><span class="spinner-border spinner-border-sm me-1"></span>불러오는 중...</p>';
    document.getElementById('mig-acc-ec2-badge').textContent = '-';
    document.getElementById('mig-acc-rds-badge').textContent = '-';

    new bootstrap.Modal(document.getElementById('migAccepterResourceModal')).show();

    // EC2/RDS, 라우팅 테이블, 보안 그룹 병렬 조회
    const sgId = migS.existing.accepterSecurityGroupId;
    const [resourceRes, rtRes, sgRes] = await Promise.allSettled([
        apiFetch(`${AWS}/vpc-resources?vpcId=${encodeURIComponent(vpcId)}&account=${account}`),
        apiFetch(`${AWS}/route-tables?vpcId=${encodeURIComponent(vpcId)}&account=${account}`),
        sgId
            ? apiFetch(`${AWS}/security-groups?sgIds=${encodeURIComponent(sgId)}&account=${account}`)
            : Promise.resolve({data: []})
    ]);

    // ── EC2 + RDS ─────────────────────────────────────────────
    if (resourceRes.status === 'fulfilled') {
        const { ec2Instances = [], rdsInstances = [], rdsError = null } = resourceRes.value.data;

        document.getElementById('mig-acc-ec2-badge').textContent = ec2Instances.length;
        document.getElementById('mig-acc-ec2-badge').className =
            `badge ms-1 ${ec2Instances.length > 0 ? 'bg-primary' : 'bg-secondary'}`;

        document.getElementById('mig-acc-rds-badge').textContent = rdsError ? '!' : rdsInstances.length;
        document.getElementById('mig-acc-rds-badge').className =
            `badge ms-1 ${rdsError ? 'bg-danger' : (rdsInstances.length > 0 ? 'bg-primary' : 'bg-secondary')}`;

        document.getElementById('mig-acc-ec2-tbody').innerHTML = ec2Instances.length === 0
            ? '<tr><td colspan="6" class="text-muted text-center py-2">EC2 인스턴스 없음</td></tr>'
            : ec2Instances.map(inst => `
                <tr class="instance-row">
                    <td><code class="small">${inst.instanceId}</code></td>
                    <td>${inst.name || '<span class="text-muted">-</span>'}</td>
                    <td><span class="badge bg-light text-dark border small">${inst.instanceType}</span></td>
                    <td>${stateIcon(inst.state)}&nbsp;${inst.state}</td>
                    <td><code class="small">${inst.privateIp || '-'}</code></td>
                    <td>
                        ${inst.securityGroupIds?.map(id => `<span class="sg-tag">${id}</span>`).join('') || '-'}
                        ${inst.securityGroupIds?.length > 0
                            ? `<button class="btn btn-sm btn-outline-info rounded-pill px-2 py-0 ms-1" style="font-size:.72rem"
                                       data-sg-ids="${inst.securityGroupIds.join(',')}"
                                       data-account="${account}"
                                       data-label="${inst.instanceId}"
                                       onclick="openSgModal(this)">&#128269; 상세</button>`
                            : ''}
                    </td>
                </tr>`).join('');

        if (rdsError) {
            document.getElementById('mig-acc-rds-tbody').innerHTML = `
                <tr><td colspan="6" class="text-danger py-2">
                    <strong>RDS 조회 오류:</strong> ${rdsError}
                    <div class="text-muted small mt-1">IAM 권한(rds:DescribeDBInstances)을 확인하세요.</div>
                </td></tr>`;
        } else {
            document.getElementById('mig-acc-rds-tbody').innerHTML = rdsInstances.length === 0
                ? '<tr><td colspan="6" class="text-muted text-center py-2">RDS 인스턴스 없음</td></tr>'
                : rdsInstances.map(db => `
                    <tr class="instance-row">
                        <td><code class="small">${db.dbInstanceId}</code></td>
                        <td>${db.engine}&nbsp;<span class="text-muted small">${db.engineVersion}</span></td>
                        <td>${dbStatusIcon(db.status)}&nbsp;${db.status}</td>
                        <td class="small">${db.endpoint || '-'}</td>
                        <td><span class="badge bg-light text-dark border small">${db.instanceClass}</span></td>
                        <td>
                            ${db.securityGroupIds?.map(id => `<span class="sg-tag">${id}</span>`).join('') || '-'}
                            ${db.securityGroupIds?.length > 0
                                ? `<button class="btn btn-sm btn-outline-info rounded-pill px-2 py-0 ms-1" style="font-size:.72rem"
                                           data-sg-ids="${db.securityGroupIds.join(',')}"
                                           data-account="${account}"
                                           data-label="${db.dbInstanceId}"
                                           onclick="openSgModal(this)">&#128269; 상세</button>`
                                : ''}
                        </td>
                    </tr>`).join('');
        }
    } else {
        const msg = resourceRes.reason?.message ?? '조회 실패';
        document.getElementById('mig-acc-ec2-tbody').innerHTML =
            `<tr><td colspan="6" class="text-danger text-center">${msg}</td></tr>`;
        document.getElementById('mig-acc-rds-tbody').innerHTML =
            `<tr><td colspan="6" class="text-danger text-center">${msg}</td></tr>`;
    }

    // ── 라우팅 테이블 ──────────────────────────────────────────
    if (rtRes.status === 'fulfilled') {
        const tables  = rtRes.value.data ?? [];
        const activeRt = migS.existing.accepterRouteTableId;
        document.getElementById('mig-acc-rt-content').innerHTML = tables.length === 0
            ? '<p class="text-muted text-center py-2">라우팅 테이블 없음</p>'
            : tables.map(rt => {
                const isActive = rt.routeTableId === activeRt;
                return `
                <div class="card mb-2 ${isActive ? 'border-success' : 'border-light'}">
                    <div class="card-header py-2 d-flex align-items-center gap-2
                                ${isActive ? 'bg-success bg-opacity-10' : 'bg-light'}">
                        <code>${rt.routeTableId}</code>
                        ${rt.main ? '<span class="badge bg-primary">Main</span>' : ''}
                        ${isActive ? '<span class="badge bg-success">피어링에 사용중</span>' : ''}
                        ${rt.name ? `<span class="text-muted small">[${rt.name}]</span>` : ''}
                        <span class="text-muted small ms-auto">
                            서브넷 ${rt.subnetIds?.length ?? 0}개 연결됨
                        </span>
                    </div>
                    ${rt.subnetIds?.length > 0 ? `
                    <div class="card-body py-2 px-3">
                        <div class="small text-muted">연결 서브넷:
                            ${rt.subnetIds.map(s => `<code class="me-1">${s}</code>`).join('')}
                        </div>
                    </div>` : ''}
                </div>`;
            }).join('');
    } else {
        document.getElementById('mig-acc-rt-content').innerHTML =
            `<div class="alert alert-danger">${rtRes.reason?.message ?? '조회 실패'}</div>`;
    }

    // ── 보안 그룹 ─────────────────────────────────────────────
    if (sgRes.status === 'fulfilled') {
        const groups = sgRes.value.data ?? [];
        document.getElementById('mig-acc-sg-content').innerHTML = !sgId
            ? '<p class="text-muted text-center py-2">이 피어링에 설정된 보안 그룹이 없습니다.</p>'
            : groups.length === 0
            ? '<p class="text-muted text-center py-2">보안 그룹을 찾을 수 없습니다.</p>'
            : groups.map(sg => renderSgCard(sg)).join('');
    } else {
        document.getElementById('mig-acc-sg-content').innerHTML =
            `<div class="alert alert-danger">${sgRes.reason?.message ?? '조회 실패'}</div>`;
    }
}

// ── Jumphost 이전 완료/롤백 모달 확인 버튼 ────────────────
document.getElementById('btn-confirm-mig-complete').addEventListener('click', executeMigrateComplete);
document.getElementById('btn-confirm-mig-rollback').addEventListener('click', executeMigrateRollback);
