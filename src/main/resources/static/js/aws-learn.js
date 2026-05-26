// ── AWS 학습 화면 — VPC / EC2 / RDS 조회 ─────────────────────────────────
const LEARN_API = '/api/v1/learn';

// ── 공통 유틸 ────────────────────────────────────────────────────────────────

/**
 * Secret Key 입력 필드의 표시/숨기기 토글.
 * @param {string}      inputId - 토글할 input 요소의 ID
 * @param {HTMLElement} btn     - 클릭한 토글 버튼
 */
function toggleLearnSecretKey(inputId, btn) {
    const input = document.getElementById(inputId);
    input.type = input.type === 'password' ? 'text' : 'password';
    btn.textContent = input.type === 'password' ? '👁️' : '🙈';
}

/**
 * pill 탭 전환 시 이전 조회 결과를 초기화한다.
 */
function learnResetResult() {
    learnSetState('idle');
}

// ── 직접 자격증명 방식 ───────────────────────────────────────────────────────

/**
 * Access Key + Secret Key로 VPC 목록을 조회한다.
 */
async function loadLearnVpcs() {
    const accessKey = document.getElementById('learn-access-key').value.trim();
    const secretKey = document.getElementById('learn-secret-key').value.trim();
    const region    = document.getElementById('learn-region').value;

    if (!accessKey || !secretKey) {
        learnShowError('Access Key와 Secret Key를 모두 입력해주세요.');
        return;
    }

    learnSetState('loading');
    try {
        const body = await apiFetch(`${LEARN_API}/vpcs`, {
            method: 'POST',
            body: JSON.stringify({ accessKey, secretKey, region })
        });
        const vpcs = body.data || [];
        if (!vpcs.length) { learnSetState('empty'); return; }
        renderDirectVpcs(vpcs, region);
        learnSetState('direct-result');
    } catch (err) {
        learnShowError(err.message || 'VPC 조회 중 오류가 발생했습니다.');
    }
}

/**
 * 직접 자격증명 결과 — VPC 테이블 렌더링.
 * @param {Array}  vpcs   - VpcInfoDto 배열
 * @param {string} region - 조회 리전
 */
function renderDirectVpcs(vpcs, region) {
    document.getElementById('learn-count-badge').textContent = vpcs.length;
    document.getElementById('learn-region-label').textContent = `리전: ${region}`;
    document.getElementById('learn-vpc-tbody').innerHTML = vpcs.map(vpcRow).join('');
}

// ── Role ARN (STS AssumeRole) 방식 ──────────────────────────────────────────

/**
 * Role ARN AssumeRole로 VPC + EC2 + RDS를 통합 조회한다.
 */
async function loadLearnVpcsByRole() {
    const accessKey   = document.getElementById('role-access-key').value.trim();
    const secretKey   = document.getElementById('role-secret-key').value.trim();
    const roleArn     = document.getElementById('role-arn').value.trim();
    const region      = document.getElementById('role-region').value;
    const sessionName = document.getElementById('role-session-name').value.trim() || null;
    const externalId  = document.getElementById('role-external-id').value.trim() || null;

    if (!accessKey || !secretKey) {
        learnShowError('Caller의 Access Key와 Secret Key를 모두 입력해주세요.');
        return;
    }
    if (!roleArn) {
        learnShowError('Role ARN을 입력해주세요.');
        return;
    }
    if (!roleArn.match(/^arn:aws[a-z-]*:iam::\d{12}:role\/.+$/)) {
        learnShowError('Role ARN 형식이 올바르지 않습니다. (예: arn:aws:iam::123456789012:role/RoleName)');
        return;
    }

    learnSetState('loading');
    try {
        const payload = { accessKey, secretKey, roleArn, region };
        if (sessionName) payload.sessionName = sessionName;
        if (externalId)  payload.externalId  = externalId;

        const body = await apiFetch(`${LEARN_API}/resources-by-role`, {
            method: 'POST',
            body: JSON.stringify(payload)
        });

        renderRoleResources(body.data, region, roleArn);
        learnSetState('role-result');
    } catch (err) {
        learnShowError(err.message || '리소스 조회 중 오류가 발생했습니다.');
    }
}

/**
 * Role ARN 통합 결과 렌더링 — VPC / EC2 / RDS 각 탭을 채운다.
 * @param {Object} data    - AwsLearnRoleResourceResponse
 * @param {string} region  - 조회 리전
 * @param {string} roleArn - 사용된 Role ARN
 */
function renderRoleResources(data, region, roleArn) {
    const vpcs = data.vpcs || [];
    const ec2  = data.ec2Instances || [];
    const rds  = data.rdsInstances || [];

    // 레이블
    document.getElementById('learn-role-region-label').textContent =
        `리전: ${region} | Role: ${escHtml(roleArn)}`;

    // 배지 카운트
    document.getElementById('learn-role-vpc-badge').textContent = vpcs.length;
    document.getElementById('learn-role-ec2-badge').textContent = ec2.length;
    document.getElementById('learn-role-rds-badge').textContent = rds.length;

    // VPC 탭
    document.getElementById('learn-role-vpc-tbody').innerHTML =
        vpcs.length ? vpcs.map(vpcRow).join('') : emptyRow(7, 'VPC 없음');

    // EC2 탭
    document.getElementById('learn-role-ec2-tbody').innerHTML =
        ec2.length ? ec2.map(ec2Row).join('') : emptyRow(8, 'EC2 인스턴스 없음');

    // RDS 탭
    const rdsErrorEl = document.getElementById('learn-role-rds-error');
    if (data.rdsError) {
        rdsErrorEl.textContent = `RDS 조회 실패 (권한 확인 필요): ${data.rdsError}`;
        rdsErrorEl.classList.remove('d-none');
    } else {
        rdsErrorEl.classList.add('d-none');
    }
    document.getElementById('learn-role-rds-tbody').innerHTML =
        rds.length ? rds.map(rdsRow).join('') : emptyRow(8, 'RDS 인스턴스 없음');
}

// ── 행 렌더러 ────────────────────────────────────────────────────────────────

/**
 * VpcInfoDto → 테이블 행 HTML.
 * @param {Object} vpc - VpcInfoDto
 */
function vpcRow(vpc) {
    const stateBadge = vpc.state === 'available'
        ? `<span class="badge bg-success">${escHtml(vpc.state)}</span>`
        : `<span class="badge bg-warning text-dark">${escHtml(vpc.state)}</span>`;

    const defaultBadge = vpc.default
        ? `<span class="badge bg-info text-dark">기본</span>`
        : `<span class="text-muted">-</span>`;

    const nonNameTags = (vpc.tags || []).filter(t => t.key !== 'Name');
    const tagBadges = nonNameTags.slice(0, 3)
        .map(t => `<span class="badge bg-light text-dark border me-1">${escHtml(t.key)}=${escHtml(t.value)}</span>`)
        .join('');
    const moreBadge = nonNameTags.length > 3
        ? `<span class="text-muted small">+${nonNameTags.length - 3}개</span>` : '';

    return `<tr>
        <td><code class="small">${escHtml(vpc.vpcId)}</code></td>
        <td>${vpc.name ? `<span class="fw-semibold">${escHtml(vpc.name)}</span>` : '<span class="text-muted">-</span>'}</td>
        <td><code class="small">${escHtml(vpc.cidrBlock)}</code></td>
        <td>${stateBadge}</td>
        <td class="text-center">${defaultBadge}</td>
        <td><code class="small">${escHtml(vpc.ownerId || '-')}</code></td>
        <td>${tagBadges}${moreBadge}</td>
    </tr>`;
}

/**
 * LearnEc2Dto → 테이블 행 HTML.
 * @param {Object} inst - LearnEc2Dto
 */
function ec2Row(inst) {
    const stateMap = { running: 'success', stopped: 'secondary', pending: 'warning', stopping: 'warning' };
    const stateBadge = `<span class="badge bg-${stateMap[inst.state] || 'secondary'}">${escHtml(inst.state)}</span>`;

    return `<tr>
        <td><code class="small">${escHtml(inst.instanceId)}</code></td>
        <td>${inst.name ? `<span class="fw-semibold">${escHtml(inst.name)}</span>` : '<span class="text-muted">-</span>'}</td>
        <td><span class="badge bg-light text-dark border">${escHtml(inst.instanceType || '-')}</span></td>
        <td>${stateBadge}</td>
        <td><code class="small">${escHtml(inst.privateIp || '-')}</code></td>
        <td><code class="small">${inst.publicIp ? escHtml(inst.publicIp) : '<span class="text-muted">-</span>'}</code></td>
        <td><code class="small">${escHtml(inst.vpcId || '-')}</code></td>
        <td><span class="text-muted small">${escHtml(inst.availabilityZone || '-')}</span></td>
    </tr>`;
}

/**
 * LearnRdsDto → 테이블 행 HTML.
 * @param {Object} db - LearnRdsDto
 */
function rdsRow(db) {
    const statusMap = { available: 'success', stopped: 'secondary', creating: 'warning', deleting: 'danger' };
    const statusBadge = `<span class="badge bg-${statusMap[db.status] || 'secondary'}">${escHtml(db.status)}</span>`;
    const multiAzBadge = db.multiAz
        ? `<span class="badge bg-primary">Multi-AZ</span>`
        : `<span class="text-muted">-</span>`;

    return `<tr>
        <td><code class="small">${escHtml(db.dbInstanceId)}</code></td>
        <td><span class="badge bg-light text-dark border">${escHtml(db.engine || '-')}</span></td>
        <td><span class="text-muted small">${escHtml(db.engineVersion || '-')}</span></td>
        <td>${statusBadge}</td>
        <td><span class="badge bg-light text-dark border">${escHtml(db.instanceClass || '-')}</span></td>
        <td><code class="small">${db.endpoint ? escHtml(db.endpoint) : '<span class="text-muted">-</span>'}</code></td>
        <td><code class="small">${escHtml(db.vpcId || '-')}</code></td>
        <td class="text-center">${multiAzBadge}</td>
    </tr>`;
}

/**
 * 데이터가 없을 때 colspan 빈 행을 반환한다.
 * @param {number} cols    - colspan 값
 * @param {string} message - 표시할 메시지
 */
function emptyRow(cols, message) {
    return `<tr><td colspan="${cols}" class="text-center text-muted py-3">${message}</td></tr>`;
}

// ── 상태 관리 ────────────────────────────────────────────────────────────────

/**
 * 에러 메시지를 표시한다.
 * @param {string} msg - 표시할 에러 메시지
 */
function learnShowError(msg) {
    const el = document.getElementById('learn-error');
    el.textContent = msg;
    el.classList.remove('d-none');
}

/**
 * 결과 영역의 화면 상태를 전환한다.
 * @param {'idle'|'loading'|'empty'|'direct-result'|'role-result'} state
 */
function learnSetState(state) {
    ['learn-loading', 'learn-empty', 'learn-result', 'learn-role-result', 'learn-error']
        .forEach(id => document.getElementById(id).classList.add('d-none'));

    if (state === 'loading')        document.getElementById('learn-loading').classList.remove('d-none');
    else if (state === 'empty')     document.getElementById('learn-empty').classList.remove('d-none');
    else if (state === 'direct-result') document.getElementById('learn-result').classList.remove('d-none');
    else if (state === 'role-result')   document.getElementById('learn-role-result').classList.remove('d-none');
    // 'idle': 모두 숨김 상태 유지
}
