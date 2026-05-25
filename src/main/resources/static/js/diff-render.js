/**
 * 단일 객체 또는 배열을 항상 배열로 정규화.
 * @param {Array|object} data
 * @returns {Array}
 */
function normSgArray(data) {
    return Array.isArray(data) ? data : [data];
}

/**
 * 레거시 스냅샷 형식 인바운드 규칙 정규화.
 * ipRanges 배열의 각 CIDR을 개별 규칙으로 분리한다.
 */
function normLegacyRules(rules) {
    if (!rules) return [];
    return rules.flatMap(r => {
        const ranges = Array.isArray(r.ipRanges) && r.ipRanges.length > 0 ? r.ipRanges : [null];
        return ranges.map(cidr => ({
            protocol : r.ipProtocol ?? '-1',
            fromPort : r.fromPort,
            toPort   : r.toPort,
            source   : cidr ?? '*'
        }));
    });
}

/**
 * 신규 형식(SecurityGroupDetailDto.SgRule) 인바운드 규칙 정규화.
 * protocol 대소문자를 소문자로 통일한다.
 */
function normTobeRules(rules) {
    if (!rules) return [];
    return rules.map(r => ({
        protocol : r.protocol === 'ALL' ? '-1' : (r.protocol ?? '-1').toLowerCase(),
        fromPort : r.fromPort,
        toPort   : r.toPort,
        source   : r.source ?? '*'
    }));
}

/** 규칙 비교 키 생성 — 프로토콜(소문자)·포트·소스 조합 */
function sgRuleKey(r) {
    const proto = r.protocol === 'ALL' ? '-1' : String(r.protocol ?? '-1').toLowerCase();
    return `${proto}:${r.fromPort}:${r.toPort}:${r.source}`;
}

/**
 * 단일 SG 객체에서 인바운드 규칙을 정규화된 형식으로 추출.
 * 신규 형식(inboundRules)과 레거시 형식(ingressRules) 모두 지원.
 *
 * @param {object} sg  단일 보안 그룹 객체
 * @returns {Array}    정규화된 규칙 배열
 */
function extractRulesFromSg(sg) {
    if (!sg) return [];
    if (sg.inboundRules) return normTobeRules(sg.inboundRules);
    if (sg.ingressRules) return normLegacyRules(sg.ingressRules);
    return [];
}

/**
 * SG 데이터(배열·단일 객체 모두 지원)에서 인바운드 규칙을 정규화된 형식으로 추출.
 *
 * 신규 형식(배열): inboundRules:[{protocol,fromPort,toPort,source}]
 * 레거시 형식(단일 객체): ingressRules:[{ipProtocol,fromPort,toPort,ipRanges:[cidr]}]
 */
function extractSgInboundRules(data) {
    if (!data) return [];
    const list = Array.isArray(data) ? data : [data];
    return list.flatMap(sg => {
        if (sg.inboundRules) {
            // 신규 배열 형식 (SecurityGroupDetailDto 호환)
            return normTobeRules(sg.inboundRules);
        } else if (sg.ingressRules) {
            // 레거시 단일 객체 형식 (구 스냅샷)
            return normLegacyRules(sg.ingressRules);
        }
        return [];
    });
}

/**
 * SG 데이터(배열 또는 단일 객체)에서 그룹 이름/ID 요약 문자열 생성.
 * 보안 그룹이 여러 개일 때 "sg-xxx (name), ..." 형태로 결합한다.
 */
function buildSgInfoLine(data) {
    if (!data) return '';
    const list = Array.isArray(data) ? data : [data];
    return list.map(sg => sg.groupId
        ? `${sg.groupId}${sg.groupName ? ' (' + sg.groupName + ')' : ''}`
        : ''
    ).filter(Boolean).join(', ');
}

/**
 * 라우팅 테이블 행 목록을 HTML 테이블로 렌더링.
 * oppositeKeys에 없는 경로를 diffClass('removed'|'added')로 강조한다.
 *
 * @param {Array}       routes        렌더링할 경로 목록
 * @param {Set}         oppositeKeys  반대쪽 CIDR 집합 (diff 판단)
 * @param {string}      diffClass     'removed' 또는 'added'
 * @param {string|null} deletedPcxId  TO-BE 탭 전용 — 완료 시 삭제될 구 PCX ID (핑크 표시)
 */
function renderRouteTable(routes, oppositeKeys, diffClass, deletedPcxId = null) {
    if (routes.length === 0) return '<div class="text-muted p-2">경로 없음</div>';

    const bgClass   = diffClass === 'removed' ? 'table-danger'  : 'table-success';
    const iconClass = diffClass === 'removed' ? '&#9660;'       : '&#9650;';

    const rows = routes.map(r => {
        const cidr    = r.destinationCidrBlock ?? '—';
        const target  = r.vpcPeeringConnectionId
            ? `<code class="text-primary">${escHtml(r.vpcPeeringConnectionId)}</code>`
            : `<span class="text-muted">${escHtml(r.gatewayId ?? '—')}</span>`;
        const state   = r.state ?? '—';

        // 완료 시 삭제될 경로(구 PCX로 향하는 경로) → 핑크 배경
        const willDelete = deletedPcxId && r.vpcPeeringConnectionId === deletedPcxId;
        const changed    = !willDelete && !oppositeKeys.has(r.destinationCidrBlock);
        const rowStyle   = willDelete ? ' style="background:#fce4ec"' : '';
        const rowCls     = willDelete ? '' : (changed ? bgClass : '');
        const icon       = willDelete
            ? '<span class="me-1 text-danger">&#9660;</span>'
            : (changed ? `<span class="me-1">${iconClass}</span>` : '');

        return `<tr class="${rowCls}"${rowStyle}>
            <td class="small text-nowrap">${icon}<code>${escHtml(cidr)}</code></td>
            <td class="small">${target}</td>
            <td class="small"><span class="badge ${state === 'active' ? 'bg-success' : 'bg-secondary'}">${escHtml(state)}</span></td>
        </tr>`;
    }).join('');

    return `<table class="table table-sm table-bordered mb-0">
        <thead class="table-light">
            <tr><th class="small">대상 CIDR</th><th class="small">대상</th><th class="small">상태</th></tr>
        </thead>
        <tbody>${rows}</tbody>
    </table>`;
}

/**
 * 보안 그룹 인바운드 규칙 목록을 HTML 테이블로 렌더링.
 *
 * @param {Array}       rules         렌더링할 규칙 목록
 * @param {Set}         oppositeKeys  반대쪽 규칙 키 집합 (diff 판단)
 * @param {string}      diffClass     'removed' 또는 'added'
 * @param {string|null} deletedCidr   TO-BE 탭 전용 — 완료 시 삭제될 구 requester CIDR (핑크 표시)
 */
function renderSgTable(rules, oppositeKeys, diffClass, deletedCidr = null) {
    if (rules.length === 0) return '<div class="text-muted p-2">규칙 없음</div>';

    const bgClass   = diffClass === 'removed' ? 'table-danger' : 'table-success';
    const iconClass = diffClass === 'removed' ? '&#9660;'      : '&#9650;';

    const rows = rules.map(r => {
        const proto   = r.protocol === '-1' ? 'ALL' : (r.protocol ?? '-1').toUpperCase();
        const port    = (r.fromPort === r.toPort)
            ? (r.fromPort === -1 ? '모두' : String(r.fromPort ?? '모두'))
            : `${r.fromPort ?? '?'} – ${r.toPort ?? '?'}`;

        // 완료 시 삭제될 규칙(구 requester CIDR이 소스인 규칙) → 핑크 배경
        const willDelete = deletedCidr && r.source === deletedCidr;
        const changed    = !willDelete && !oppositeKeys.has(sgRuleKey(r));
        const rowStyle   = willDelete ? ' style="background:#fce4ec"' : '';
        const rowCls     = willDelete ? '' : (changed ? bgClass : '');
        const icon       = willDelete
            ? '<span class="me-1 text-danger">&#9660;</span>'
            : (changed ? `<span class="me-1">${iconClass}</span>` : '');

        return `<tr class="${rowCls}"${rowStyle}>
            <td class="small">${icon}<span class="badge bg-secondary">${escHtml(proto)}</span></td>
            <td class="small">${escHtml(port)}</td>
            <td class="small"><code>${escHtml(r.source ?? '*')}</code></td>
        </tr>`;
    }).join('');

    return `<table class="table table-sm table-bordered mb-0">
        <thead class="table-light">
            <tr><th class="small">프로토콜</th><th class="small">포트</th><th class="small">소스(CIDR)</th></tr>
        </thead>
        <tbody>${rows}</tbody>
    </table>`;
}

/**
 * 보안 그룹 목록을 각 그룹별 헤더 + 규칙 테이블로 렌더링.
 * 동일 groupId 의 반대쪽 규칙과 비교하여 diff 하이라이트를 적용한다.
 *
 * @param {Array}       thisSide    렌더링할 SG 배열
 * @param {Array}       otherSide   비교 기준 SG 배열 (반대쪽)
 * @param {string}      diffClass   'removed' 또는 'added'
 * @param {string|null} deletedCidr TO-BE 탭 전용 — 완료 시 삭제될 구 requester CIDR (핑크 표시)
 */
function renderSgGrouped(thisSide, otherSide, diffClass, deletedCidr = null) {
    if (!thisSide || thisSide.length === 0) {
        return '<div class="text-muted p-2">규칙 없음</div>';
    }
    return thisSide.map(sg => {
        const otherSg   = otherSide.find(o => o.groupId === sg.groupId);
        const thisRules = extractRulesFromSg(sg);
        const otherKeys = new Set((otherSg ? extractRulesFromSg(otherSg) : []).map(sgRuleKey));

        const header = `<div class="px-3 py-2 small fw-semibold bg-light border-bottom border-top mt-2">` +
            `<code>${escHtml(sg.groupId ?? '')}</code>` +
            (sg.groupName ? ` <span class="text-muted fw-normal">(${escHtml(sg.groupName)})</span>` : '') +
            `</div>`;

        return header + renderSgTable(thisRules, otherKeys, diffClass, deletedCidr);
    }).join('');
}

/**
 * 요약 뷰 공통 렌더러 — 라우팅 테이블 표 + 보안 그룹 그룹별 표.
 * otherRtData·otherSgData를 기준으로 diff 하이라이트를 적용한다.
 *
 * @param {HTMLElement}       el          렌더링 대상 요소
 * @param {object|null}       rtData      이쪽 RT 데이터 {routeTableId, routes[]}
 * @param {Array|object|null} sgData      이쪽 SG 데이터
 * @param {object|null}       otherRtData 반대쪽 RT 데이터 (diff 비교 기준)
 * @param {Array|object|null} otherSgData 반대쪽 SG 데이터 (diff 비교 기준)
 * @param {'removed'|'added'} diffClass   'removed'=빨간색, 'added'=녹색
 * @param {string|null}       deletedPcxId  TO-BE 탭 전용 — 완료 시 삭제될 구 PCX ID
 * @param {string|null}       deletedCidr   TO-BE 탭 전용 — 완료 시 삭제될 구 requester CIDR
 */
function renderMigDetailSection(el, rtData, sgData, otherRtData, otherSgData, diffClass,
                                deletedPcxId = null, deletedCidr = null) {
    let html = '';

    // 라우팅 테이블 섹션
    const routes      = rtData?.routes ?? [];
    const otherRoutes = otherRtData?.routes ?? [];
    const otherRtKeys = new Set(otherRoutes.map(r => r.destinationCidrBlock));

    html += '<div class="mb-4">';
    html += '<div class="fw-semibold small text-secondary mb-2">&#128204; 라우팅 테이블';
    if (rtData?.routeTableId) {
        html += ` <code class="fw-normal text-muted">${escHtml(rtData.routeTableId)}</code>`;
    }
    html += '</div>';
    if (routes.length > 0) {
        html += renderRouteTable(routes, otherRtKeys, diffClass, deletedPcxId);
    } else {
        html += `<div class="text-muted small">${rtData ? '경로 없음' : '라우팅 테이블 데이터 없음'}</div>`;
    }
    html += '</div>';

    // 보안 그룹 섹션
    const sgArr      = sgData      ? (Array.isArray(sgData)      ? sgData      : [sgData])      : [];
    const otherSgArr = otherSgData ? (Array.isArray(otherSgData) ? otherSgData : [otherSgData]) : [];

    html += '<div>';
    html += '<div class="fw-semibold small text-secondary mb-2">&#128274; 보안 그룹</div>';
    if (sgArr.length > 0) {
        html += renderSgGrouped(sgArr, otherSgArr, diffClass, deletedCidr);
    } else {
        html += '<div class="text-muted small">보안 그룹 데이터 없음</div>';
    }
    html += '</div>';

    el.innerHTML = html;
}

/**
 * 스냅샷 목록을 JSON pre 블록으로 렌더링.
 *
 * @param {HTMLElement} el        렌더링 대상 요소
 * @param {Array}       snapshots dataType·snapshotData 포함 스냅샷 배열
 */
function renderSnapshotsJson(el, snapshots) {
    el.innerHTML = snapshots.map(s => {
        const parsed = tryParseJson(s.snapshotData);
        return `<div class="mb-3">
            <div class="fw-bold small text-secondary mb-1">${escHtml(s.dataType)}</div>
            <pre class="snapshot-json">${escHtml(JSON.stringify(parsed ?? s.snapshotData, null, 2))}</pre>
        </div>`;
    }).join('') || '<div class="text-muted">데이터 없음</div>';
}
