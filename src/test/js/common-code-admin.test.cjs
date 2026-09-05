const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/admin/common-codes.js'), 'utf8');
const ACCESS = 'common-code-admin.access-token';
const REFRESH = 'common-code-admin.request-token';
const WARNING = '이 브라우저에서는 로그아웃했지만 서버 로그아웃을 확인하지 못했습니다.';
const response = (status = 200, data = null) => new Response(JSON.stringify({ data }), { status });
const tokens = () => response(200, { accessToken: 'new-access', requestToken: 'new-refresh' });
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const flush = async () => { for (let i = 0; i < 4; i++) await new Promise(setImmediate); };

function page(handler, authenticated = true) {
    const elements = new Map();
    function element(id) {
        if (!elements.has(id)) {
            const e = { value: '', textContent: '', innerHTML: '', className: '', disabled: false,
                listeners: {}, checked: false, readOnly: false,
                addEventListener(type, fn) { this.listeners[type] = fn; },
                reset() { this.value = ''; },
                replaceChildren() { this.innerHTML = ''; },
                querySelectorAll() { return [element('phoneNumber'), element('pin'), element('loginSubmit')]; },
                classList: null };
            e.classList = {
                contains: name => e.className.split(' ').includes(name),
                toggle(name, force) { const set = new Set(e.className.split(' ').filter(Boolean));
                    if (force === undefined ? !set.has(name) : force) set.add(name); else set.delete(name);
                    e.className = [...set].join(' '); },
                add(name) { this.toggle(name, true); }, remove(name) { this.toggle(name, false); }
            };
            elements.set(id, e);
        }
        return elements.get(id);
    }
    const storage = new Map(authenticated ? [[ACCESS, 'old-access'], [REFRESH, 'old-refresh']] : []);
    const writes = [], calls = [], timers = new Map();
    let timerId = 0;
    vm.runInNewContext(source, {
        document: { getElementById: element, querySelectorAll: () => [] },
        sessionStorage: { getItem: key => storage.get(key) ?? null,
            setItem: (key, value) => { writes.push([key, value]); storage.set(key, value); },
            removeItem: key => storage.delete(key) },
        fetch: (url, options = {}) => { calls.push({ url, options });
            return handler(url, options) ?? Promise.resolve(response(200, [])); },
        Headers, AbortController,
        setTimeout: (fn, ms) => { timers.set(++timerId, { fn, ms }); return timerId; },
        clearTimeout: id => timers.delete(id),
        window: { prompt: () => null }
    });
    return { element, storage, writes, calls, timers,
        logout: () => element('logoutButton').listeners.click(),
        login: () => element('loginForm').listeners.submit({ preventDefault() {} }),
        search: () => element('searchForm').listeners.submit({ preventDefault() {} }) };
}
function assertCleared(p) {
    assert.equal(p.storage.size, 0);
    assert.equal(p.element('pin').value, '');
    assert.equal(p.element('groupList').innerHTML, '');
    assert.equal(p.element('detailRows').innerHTML, '');
    assert.equal(p.element('adminPanel').classList.contains('hidden'), true);
    assert.equal(p.element('loginPanel').classList.contains('hidden'), false);
    assert.equal(p.element('logoutButton').disabled, false);
    assert.equal(p.element('loginSubmit').disabled, false);
    assert.equal(p.timers.size, 0);
}

test('normal logout revokes before clearing credentials and editor', async () => {
    const pending = deferred();
    const p = page(url => url.endsWith('/logout') ? pending.promise : undefined);
    await flush();
    p.element('pin').value = 'synthetic'; p.element('detailRows').innerHTML = 'private rows';
    const done = p.logout();
    assert.equal(p.storage.has(ACCESS), true);
    assert.equal(p.calls.at(-1).options.headers.Authorization, 'Bearer old-access');
    pending.resolve(response()); await done;
    assertCleared(p); assert.equal(p.element('message').classList.contains('hidden'), true);
});

test('no credentials performs local cleanup without a request', async () => {
    const p = page(() => undefined, false); await p.logout();
    assert.equal(p.calls.length, 0); assertCleared(p);
});

test('expired access refreshes once and revokes using only temporary tokens', async () => {
    let attempts = 0;
    const p = page(url => url.endsWith('/logout') ? Promise.resolve(response(++attempts === 1 ? 401 : 200))
        : url.endsWith('/token/refresh') ? Promise.resolve(tokens()) : undefined);
    await flush(); await p.logout();
    const auth = p.calls.filter(call => call.url.includes('/auth/'));
    assert.equal(auth.length, 3);
    assert.equal(auth[2].options.headers.Authorization, 'Bearer new-access');
    assert.equal(p.writes.length, 0); assertCleared(p);
});

for (const status of [401, 403, 500]) {
    test(`refresh ${status} clears locally; only non-401 displays warning`, async () => {
        const p = page(url => url.endsWith('/logout') ? Promise.resolve(response(401))
            : url.endsWith('/token/refresh') ? Promise.resolve(response(status)) : undefined);
        await flush(); await p.logout(); assertCleared(p);
        assert.equal(p.element('message').classList.contains('hidden'), status === 401);
        if (status !== 401) assert.equal(p.element('message').textContent, WARNING);
    });
}
for (const status of [403, 500]) {
    test(`logout ${status} clears locally and reports remote failure`, async () => {
        const p = page(url => url.endsWith('/logout') ? Promise.resolve(response(status)) : undefined);
        await flush(); await p.logout(); assertCleared(p);
        assert.equal(p.element('message').textContent, WARNING);
    });
}

test('final logout 401 after one refresh is treated as expired', async () => {
    const p = page(url => url.endsWith('/logout') ? Promise.resolve(response(401))
        : url.endsWith('/token/refresh') ? Promise.resolve(tokens()) : undefined);
    await flush(); await p.logout(); assertCleared(p);
    assert.equal(p.calls.filter(c => c.url.endsWith('/token/refresh')).length, 1);
    assert.equal(p.element('message').classList.contains('hidden'), true);
});

test('401 without refresh token is treated as expired', async () => {
    const p = page(url => url.endsWith('/logout') ? Promise.resolve(response(401)) : undefined);
    p.storage.delete(REFRESH); await flush(); await p.logout(); assertCleared(p);
    assert.equal(p.calls.filter(c => c.url.endsWith('/token/refresh')).length, 0);
});

test('network failure clears credentials and presents warning', async () => {
    const p = page(url => url.endsWith('/logout') ? Promise.reject(new TypeError('offline')) : undefined);
    await flush(); await p.logout(); assertCleared(p); assert.equal(p.element('message').textContent, WARNING);
});

for (const blocked of ['logout', 'refresh', 'retry']) {
    test(`one ten-second deadline covers ${blocked}`, async () => {
        let revokes = 0;
        const p = page((url, options) => {
            if (!url.includes('/auth/')) return;
            const stage = url.endsWith('/token/refresh') ? 'refresh' : ++revokes === 1 ? 'logout' : 'retry';
            if (stage === blocked) return new Promise((resolve, reject) => options.signal.addEventListener('abort', () => reject(new Error('aborted'))));
            return Promise.resolve(stage === 'refresh' ? tokens() : response(401));
        });
        await flush(); const done = p.logout(); await flush();
        assert.equal(p.timers.size, 1);
        const timer = [...p.timers.values()][0]; assert.equal(timer.ms, 10000); timer.fn();
        await done; assertCleared(p); assert.equal(p.element('message').textContent, WARNING);
    });
}

test('duplicate logout and login attempts are ignored during revocation', async () => {
    const pending = deferred(); const p = page(url => url.endsWith('/logout') ? pending.promise : undefined);
    await flush(); const done = p.logout(); await p.logout(); await p.login();
    assert.equal(p.element('loginSubmit').disabled, true);
    assert.equal(p.calls.filter(c => c.url.includes('/auth/')).length, 1);
    pending.resolve(response()); await done; assertCleared(p);
});

test('late successful login cannot restore authentication after logout', async () => {
    const pending = deferred(); const p = page(url => url.endsWith('/login') ? pending.promise : undefined, false);
    const login = p.login(); await p.logout(); pending.resolve(tokens()); await login;
    assertCleared(p); assert.equal(p.writes.length, 0);
});

test('late refresh cannot save tokens or retry a protected request after logout', async () => {
    const pending = deferred();
    const p = page(url => url.endsWith('/token/refresh') ? pending.promise
        : url.includes('/admin/') ? Promise.resolve(response(401)) : Promise.resolve(response()));
    await flush(); await p.logout(); pending.resolve(tokens()); await flush();
    assertCleared(p); assert.equal(p.writes.length, 0);
    assert.equal(p.calls.filter(c => c.url.includes('/admin/')).length, 1);
});

test('late protected response cannot refill the cleared page', async () => {
    const pending = deferred(); const p = page(url => url.includes('/admin/') ? pending.promise : Promise.resolve(response()));
    await p.logout(); pending.resolve(response(200, [{ groupCode: 'private', groupName: 'private', detailCount: 1 }]));
    await flush(); assertCleared(p);
});

test('late login failure cannot overwrite logout warning', async () => {
    const pending = deferred();
    const p = page(url => url.endsWith('/login') ? pending.promise
        : url.endsWith('/logout') ? Promise.resolve(response(500)) : undefined, false);
    const login = p.login(); p.storage.set(ACCESS, 'old-access'); await p.logout();
    pending.reject(new Error('late failure')); await login;
    assert.equal(p.element('message').textContent, WARNING); assertCleared(p);
});

test('logout warning lives outside the hidden admin panel', () => {
    const html = fs.readFileSync(path.join(__dirname, '../../main/resources/templates/admin/common-codes.html'), 'utf8');
    assert.ok(html.indexOf('id="message"') < html.indexOf('<section id="loginPanel"'));
});
