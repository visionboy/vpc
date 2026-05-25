function loadingRow(cols) {
    return `<tr><td colspan="${cols}" class="text-muted text-center py-2">
                <span class="spinner-border spinner-border-sm me-1"></span>조회 중...</td></tr>`;
}

function stateIcon(s) {
    return {running:'🟢', stopped:'🔴', pending:'🟡', stopping:'🟠'}[s] || '⚪';
}
function dbStatusIcon(s) {
    return {available:'🟢', stopped:'🔴', creating:'🟡', deleting:'🟠'}[s] || '⚪';
}

// ════════════════════════════════════════════════════════════
// VPC 통합 조회 (라우팅 테이블 + 리소스 병렬 실행)
// ════════════════════════════════════════════════════════════
async function loadVpcInfo(account) {
    const vpcId = document.getElementById(account + 'VpcId').value.trim();
    if (!vpcId) { showToast('VPC ID를 먼저 입력해주세요.', 'error'); return; }
    if (!vpcId.startsWith('vpc-')) {
        showToast('올바른 VPC ID 형식이 아닙니다. (예: vpc-xxxxxxxxxxxxxxxxx)', 'error'); return;
    }
    // 병렬 실행
    await Promise.all([
        loadRouteTables(account, vpcId),
        loadVpcResources(account, vpcId)
    ]);
}

// ── 라우팅 테이블 조회 ──────────────────────────────────────
async function loadRouteTables(account, vpcId) {
    const select = document.getElementById(account + 'RouteTableId');
    const status = document.getElementById(account + '-rt-status');

    select.disabled = true;
    select.innerHTML = '<option>조회 중...</option>';
    status.textContent = '조회 중...';

    try {
        const data = await apiFetch(
            `${AWS}/route-tables?vpcId=${encodeURIComponent(vpcId)}&account=${account}`);
        const tables = data.data ?? [];

        if (tables.length === 0) {
            select.innerHTML = '<option value="">라우팅 테이블 없음</option>';
            status.textContent = '(결과 없음)';
            return;
        }
        select.innerHTML = '<option value="">선택하세요</option>' +
            tables.map(rt => {
                const label = [
                    rt.main ? '★ Main' : '',
                    rt.name ? `[${rt.name}]` : '',
                    rt.subnetIds?.length ? `서브넷 ${rt.subnetIds.length}개` : '서브넷 없음'
                ].filter(Boolean).join(' · ');
                return `<option value="${rt.routeTableId}">${rt.routeTableId}  ${label}</option>`;
            }).join('');

        const main = tables.find(rt => rt.main);
        if (main) select.value = main.routeTableId;
        status.textContent = `(${tables.length}개)`;
    } catch (e) {
        select.innerHTML = '<option value="">조회 실패</option>';
        status.textContent = '';
        showToast('라우팅 테이블 조회 실패: ' + e.message, 'error');
    } finally {
        select.disabled = false;
    }
}

// ── VPC 리소스 조회 (EC2 + RDS) ────────────────────────────
async function loadVpcResources(account, vpcId) {
    const panel   = document.getElementById(account + '-resources-panel');
    const badge   = document.getElementById(account + '-vpc-badge');
    const spinner = document.getElementById(account + '-res-spinner');
    const ec2Body = document.getElementById(account + '-ec2-tbody');
    const rdsBody = document.getElementById(account + '-rds-tbody');
    const ec2Cnt  = document.getElementById(account + '-ec2-badge');
    const rdsCnt  = document.getElementById(account + '-rds-badge');

    panel.classList.remove('d-none');
    badge.textContent = vpcId;
    spinner.classList.remove('d-none');
    ec2Body.innerHTML = loadingRow(6);
    rdsBody.innerHTML = loadingRow(6);

    try {
        const data = await apiFetch(
            `${AWS}/vpc-resources?vpcId=${encodeURIComponent(vpcId)}&account=${account}`);
        const { ec2Instances = [], rdsInstances = [], rdsError = null } = data.data;

        ec2Cnt.textContent = ec2Instances.length;
        rdsCnt.textContent = rdsError ? '!' : rdsInstances.length;
        ec2Cnt.className = `badge ms-1 ${ec2Instances.length > 0 ? 'bg-primary' : 'bg-secondary'}`;
        rdsCnt.className = `badge ms-1 ${rdsError ? 'bg-danger' : (rdsInstances.length > 0 ? 'bg-primary' : 'bg-secondary')}`;

        // EC2 행 렌더링
        ec2Body.innerHTML = ec2Instances.length === 0
            ? '<tr><td colspan="6" class="text-muted text-center py-2">EC2 인스턴스 없음</td></tr>'
            : ec2Instances.map(inst => `
                <tr class="instance-row">
                    <td><code class="small">${inst.instanceId}</code></td>
                    <td>${inst.name || '<span class="text-muted">-</span>'}</td>
                    <td><span class="badge bg-light text-dark border small">${inst.instanceType}</span></td>
                    <td>${stateIcon(inst.state)}&nbsp;${inst.state}</td>
                    <td><code class="small">${inst.privateIp || '-'}</code></td>
                    <td>
                        ${inst.securityGroupIds?.map(id =>
                            `<span class="sg-tag">${id}</span>`).join('') || '-'}
                        ${inst.securityGroupIds?.length > 0
                            ? `<button class="btn btn-sm btn-outline-info rounded-pill px-2 py-0 ms-1" style="font-size:.72rem"
                                       data-sg-ids="${inst.securityGroupIds.join(',')}"
                                       data-account="${account}"
                                       data-label="${inst.instanceId}"
                                       onclick="openSgModal(this)">&#128269; 상세</button>`
                            : ''}
                    </td>
                </tr>`).join('');

        // RDS 행 렌더링
        if (rdsError) {
            rdsBody.innerHTML = `<tr><td colspan="6" class="text-danger py-2">
                <strong>RDS 조회 오류:</strong> ${rdsError}
                <div class="text-muted small mt-1">IAM 권한(rds:DescribeDBInstances)을 확인하세요.</div>
            </td></tr>`;
        } else
        rdsBody.innerHTML = rdsInstances.length === 0
            ? '<tr><td colspan="6" class="text-muted text-center py-2">RDS 인스턴스 없음</td></tr>'
            : rdsInstances.map(db => `
                <tr class="instance-row">
                    <td><code class="small">${db.dbInstanceId}</code></td>
                    <td>${db.engine}&nbsp;<span class="text-muted small">${db.engineVersion}</span></td>
                    <td>${dbStatusIcon(db.status)}&nbsp;${db.status}</td>
                    <td class="small">${db.endpoint || '-'}</td>
                    <td><span class="badge bg-light text-dark border small">${db.instanceClass}</span></td>
                    <td>
                        ${db.securityGroupIds?.map(id =>
                            `<span class="sg-tag">${id}</span>`).join('') || '-'}
                        ${db.securityGroupIds?.length > 0
                            ? `<button class="btn btn-sm btn-outline-info rounded-pill px-2 py-0 ms-1" style="font-size:.72rem"
                                       data-sg-ids="${db.securityGroupIds.join(',')}"
                                       data-account="${account}"
                                       data-label="${db.dbInstanceId}"
                                       onclick="openSgModal(this)">&#128269; 상세</button>`
                            : ''}
                    </td>
                </tr>`).join('');

    } catch (e) {
        ec2Body.innerHTML = `<tr><td colspan="6" class="text-danger text-center">${e.message}</td></tr>`;
        rdsBody.innerHTML = `<tr><td colspan="6" class="text-danger text-center">${e.message}</td></tr>`;
    } finally {
        spinner.classList.add('d-none');
    }
}

// ════════════════════════════════════════════════════════════
// 보안 그룹 상세 모달
// ════════════════════════════════════════════════════════════
async function openSgModal(btn) {
    const sgIds   = btn.dataset.sgIds.split(',').filter(Boolean);
    const account = btn.dataset.account;
    const label   = btn.dataset.label;

    document.getElementById('sg-modal-title').textContent =
        `보안 그룹 상세 — ${label}`;
    const body = document.getElementById('sg-modal-body');
    body.innerHTML = '<p class="text-center text-muted"><span class="spinner-border spinner-border-sm me-1"></span>불러오는 중...</p>';
    new bootstrap.Modal(document.getElementById('sgModal')).show();

    try {
        const data = await apiFetch(
            `${AWS}/security-groups?sgIds=${encodeURIComponent(sgIds.join(','))}&account=${account}`);
        const groups = data.data ?? [];

        body.innerHTML = groups.length === 0
            ? '<p class="text-muted text-center">연결된 보안 그룹 없음</p>'
            : groups.map(sg => renderSgCard(sg)).join('');
    } catch (e) {
        body.innerHTML = `<div class="alert alert-danger">${e.message}</div>`;
    }
}

function renderSgCard(sg) {
    return `
    <div class="card mb-3 border-secondary">
        <div class="card-header bg-light d-flex align-items-center gap-2 py-2">
            <code class="text-secondary">${sg.groupId}</code>
            <strong>${sg.groupName}</strong>
            <span class="text-muted small ms-auto">${sg.description || ''}</span>
        </div>
        <div class="card-body p-0">
            <div class="p-2 border-bottom">
                <span class="badge bg-danger rule-badge mb-2">인바운드 규칙 (${sg.inboundRules?.length || 0})</span>
                ${renderRuleTable(sg.inboundRules)}
            </div>
            <div class="p-2">
                <span class="badge bg-secondary rule-badge mb-2">아웃바운드 규칙 (${sg.outboundRules?.length || 0})</span>
                ${renderRuleTable(sg.outboundRules)}
            </div>
        </div>
    </div>`;
}

function renderRuleTable(rules) {
    if (!rules || rules.length === 0)
        return '<p class="text-muted small mb-0 ms-1">규칙 없음</p>';
    return `
    <table class="table table-sm mb-0 small">
        <thead class="table-light">
            <tr><th>프로토콜</th><th>포트 범위</th><th>소스 / 대상</th><th>설명</th></tr>
        </thead>
        <tbody>
        ${rules.map(r => `<tr>
            <td><span class="badge bg-info text-dark">${fmtProto(r.protocol)}</span></td>
            <td>${fmtPort(r.fromPort, r.toPort)}</td>
            <td><code class="small">${r.source || '-'}</code></td>
            <td class="text-muted">${r.description || '-'}</td>
        </tr>`).join('')}
        </tbody>
    </table>`;
}

function fmtProto(p) { return (!p || p === 'ALL') ? 'ALL' : p.toUpperCase(); }
function fmtPort(from, to) {
    if (from == null || from === -1) return '전체';
    return from === to ? String(from) : `${from} – ${to}`;
}

// ════════════════════════════════════════════════════════════
// 스냅샷 모달
// ════════════════════════════════════════════════════════════
async function openSnapshotModal(historyId) {
    const body = document.getElementById('snapshot-modal-body');
    body.innerHTML = '<p class="text-center text-muted">불러오는 중...</p>';
    new bootstrap.Modal(document.getElementById('snapshotModal')).show();
    try {
        const data = await apiFetch(`${API}/${historyId}/snapshots`);
        const list = data.data ?? [];
        body.innerHTML = list.length === 0
            ? '<p class="text-muted text-center">저장된 스냅샷 없음</p>'
            : list.map(s => `
                <div class="mb-4">
                    <h6 class="fw-bold text-secondary">${s.dataType}
                        <span class="fw-normal small ms-2">${fmt(s.createdAt)}</span></h6>
                    <pre class="snapshot-json">${highlight(s.snapshotData)}</pre>
                </div>`).join('<hr>');
    } catch (e) {
        body.innerHTML = `<div class="alert alert-danger">${e.message}</div>`;
    }
}

function highlight(json) {
    try {
        const s = JSON.stringify(JSON.parse(json), null, 2)
            .replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
        return s.replace(
            /("(\\u[\da-fA-F]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+\-]?\d+)?)/g,
            m => {
                let c = 'color:#b5cea8';
                if (/^"/.test(m)) c = /:$/.test(m) ? 'color:#4ec9b0' : 'color:#ce9178';
                else if (/true|false/.test(m)) c = 'color:#569cd6';
                else if (/null/.test(m)) c = 'color:#808080';
                return `<span style="${c}">${m}</span>`;
            });
    } catch { return json; }
}

// ════════════════════════════════════════════════════════════
// 생성 폼 제출
// ════════════════════════════════════════════════════════════
document.getElementById('create-form').addEventListener('submit', async e => {
    e.preventDefault();
    const form = e.target;
    const btn  = document.getElementById('btn-create');

    const required = ['requesterVpcId','requesterCidr','accepterVpcId','accepterCidr'];
    if (required.some(n => !form[n]?.value.trim())) {
        showToast('필수 항목을 모두 입력해주세요.', 'error'); return;
    }
    if (!document.getElementById('requesterRouteTableId').value) {
        showToast('요청자 라우팅 테이블을 선택해주세요.', 'error'); return;
    }
    if (!document.getElementById('accepterRouteTableId').value) {
        showToast('수락자 라우팅 테이블을 선택해주세요.', 'error'); return;
    }
    const c1 = form['requesterCidr'].value.trim();
    const c2 = form['accepterCidr'].value.trim();
    if (isCidrOverlapping(c1, c2)) {
        showToast(`CIDR 대역이 겹칩니다: ${c1} ↔ ${c2}`, 'error'); return;
    }

    const payload = {};
    new FormData(form).forEach((v, k) => { if (v) payload[k] = v; });

    btn.disabled = true; btn.textContent = '연결 중...';
    try {
        const res = await apiFetch(API, {method:'POST', body:JSON.stringify(payload)});
        showToast(`피어링 생성 완료 — ${res.data?.peeringConnectionId ?? ''}`);
        form.reset();
        ['requester','accepter'].forEach(acc => {
            document.getElementById(acc + 'RouteTableId').innerHTML =
                '<option value="">VPC ID 입력 후 [조회] 클릭</option>';
            document.getElementById(acc + '-rt-status').textContent = '';
            document.getElementById(acc + '-resources-panel').classList.add('d-none');
        });
        document.getElementById('cidr-overlap-alert').classList.add('d-none');
        document.querySelector('[data-bs-target="#tab-active"]').click();
    } catch (e) {
        showToast('생성 실패: ' + e.message, 'error');
    } finally {
        btn.disabled = false; btn.textContent = '🔗 피어링 연결하기';
    }
});
