// ════════════════════════════════════════════════════════════
// CIDR 겹침 실시간 검사
// ════════════════════════════════════════════════════════════
const CIDR_RE = /^(\d{1,3}\.){3}\d{1,3}\/\d{1,2}$/;
function ipToLong(ip) {
    return ip.split('.').reduce((acc, o) => ((acc << 8) + parseInt(o)) >>> 0, 0);
}
function isCidrOverlapping(c1, c2) {
    if (!CIDR_RE.test(c1) || !CIDR_RE.test(c2)) return false;
    const parse = c => {
        const [ip, p] = c.split('/');
        const prefix = parseInt(p);
        const mask = prefix === 0 ? 0 : (~0 << (32 - prefix)) >>> 0;
        const net  = (ipToLong(ip) & mask) >>> 0;
        return [net, (net | (~mask >>> 0)) >>> 0];
    };
    const [n1, b1] = parse(c1), [n2, b2] = parse(c2);
    return n1 <= b2 && n2 <= b1;
}

function checkCidrOverlap() {
    const c1 = document.getElementById('requesterCidr').value.trim();
    const c2 = document.getElementById('accepterCidr').value.trim();
    const alert = document.getElementById('cidr-overlap-alert');
    const i1 = document.getElementById('requesterCidr');
    const i2 = document.getElementById('accepterCidr');

    if (!CIDR_RE.test(c1) || !CIDR_RE.test(c2)) {
        alert.classList.add('d-none');
        [i1, i2].forEach(el => el.classList.remove('cidr-ok','cidr-warn'));
        return;
    }
    const overlap = isCidrOverlapping(c1, c2);
    alert.classList.toggle('d-none', !overlap);
    [i1, i2].forEach(el => {
        el.classList.toggle('cidr-warn', overlap);
        el.classList.toggle('cidr-ok',  !overlap);
    });
}
