/**
 * D35 前端轻量测试（Node 内置 test runner，无第三方依赖）。
 *
 * 运行：node --test front-end-safety.test.js
 *
 * 覆盖：
 * 1. 真实 app.js 的 isSafeUrl：危险协议/本机路径/内网/环回拦截，公网 http(s) 放行；
 * 2. 静态契约：源码无 innerHTML 赋值，使用 replaceChildren，外链带 noopener noreferrer。
 */
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

// ---- 最小 DOM 桩：让 IIFE 在无浏览器环境可加载 ----
function stubElement() {
    return {
        addEventListener() {},
        appendChild() {},
        removeChild() {},
        replaceChildren() {},
        classList: { add() {}, remove() {} },
        style: {},
        textContent: '',
        value: '',
        disabled: false,
        checked: false,
        hidden: false,
        options: [],
        selectedIndex: 0,
        parentNode: null,
        scrollTop: 0
    };
}

const elements = {};
global.document = {
    getElementById(id) {
        if (!elements[id]) {
            elements[id] = stubElement();
        }
        return elements[id];
    },
    createElement() {
        return stubElement();
    },
    createTextNode() {
        return stubElement();
    }
};

global.fetch = function () {
    return Promise.resolve({
        ok: true,
        json: () => Promise.resolve({ code: 500, data: null, msg: 'stub' }),
        text: () => Promise.resolve('{}')
    });
};

const appJsPath = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'js', 'app.js');
const app = require(appJsPath);
const source = fs.readFileSync(appJsPath, 'utf8');

test('isSafeUrl 放行公网 http/https', () => {
    assert.strictEqual(app.isSafeUrl('https://example.com/manual'), true);
    assert.strictEqual(app.isSafeUrl('http://example.com/path?x=1'), true);
});

test('isSafeUrl 拦截危险协议', () => {
    assert.strictEqual(app.isSafeUrl('javascript:alert(1)'), false);
    assert.strictEqual(app.isSafeUrl('data:text/html,x'), false);
    assert.strictEqual(app.isSafeUrl('file:///etc/passwd'), false);
    assert.strictEqual(app.isSafeUrl('ftp://files.example.com/a'), false);
});

test('isSafeUrl 拦截 localhost/环回/内网与本机路径', () => {
    assert.strictEqual(app.isSafeUrl('http://localhost:8080/x'), false);
    assert.strictEqual(app.isSafeUrl('http://127.0.0.1/x'), false);
    assert.strictEqual(app.isSafeUrl('http://192.168.1.10/x'), false);
    assert.strictEqual(app.isSafeUrl('http://10.0.0.5/x'), false);
    assert.strictEqual(app.isSafeUrl('C:\\Users\\zl\\secret.txt'), false);
});

test('isSafeUrl 对非字符串/空值返回 false', () => {
    assert.strictEqual(app.isSafeUrl(''), false);
    assert.strictEqual(app.isSafeUrl(undefined), false);
    assert.strictEqual(app.isSafeUrl(null), false);
});

test('源码无 innerHTML 赋值，使用安全 DOM API', () => {
    assert.doesNotMatch(source, /\.innerHTML\s*=/);
    assert.match(source, /replaceChildren/);
});

test('外链仅在安全时创建，且带 noopener noreferrer', () => {
    assert.match(source, /rel\s*=\s*'noopener noreferrer'/);
    assert.match(source, /target\s*=\s*'_blank'/);
    assert.match(source, /isSafeUrl\(item\.url\)/);
});
