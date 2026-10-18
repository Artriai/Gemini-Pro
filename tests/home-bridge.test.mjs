import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import {parseHTML} from 'linkedom';

const source = readFileSync(new URL('../app/src/main/assets/home_bridge.js', import.meta.url), 'utf8');
const fixture = readFileSync(new URL('./fixtures/aistudio-home.html', import.meta.url), 'utf8');

function workspace({temporary = false, indicator = temporary, origin = 'https://aistudio.google.com', frame = false} = {}) {
    const {window} = parseHTML(fixture);
    const {document} = window;
    let clicks = 0;
    window.HTMLElement.prototype.getBoundingClientRect = function () {
        return {width: this.closest('[hidden]') ? 0 : 200, height: 40, top: 80};
    };
    const pageWindow = {};
    pageWindow.top = frame ? {} : pageWindow;
    const context = vm.createContext({window: pageWindow, document, HTMLTextAreaElement: window.HTMLTextAreaElement,
        location: {origin, href: origin + '/prompts/new_chat' + (temporary ? '?temporary=true' : '')},
        getComputedStyle: el => ({display: el.style.display || 'block', visibility: 'visible', opacity: '1'}),
        Event: window.Event, KeyboardEvent: class extends window.Event {}, URL, console});
    if (indicator) document.querySelector('h1').textContent = 'Temporary chat';
    const panel = document.querySelector('ms-right-side-panel');
    document.querySelector('[aria-label="Show run settings"]').onclick = () => panel.removeAttribute('hidden');
    document.querySelector('[aria-label="Close run settings panel"]').onclick = () => panel.setAttribute('hidden', '');
    const close = () => document.querySelectorAll('.cdk-overlay-pane, .cdk-overlay-backdrop').forEach(e => e.remove());
    function overlay() {
        close();
        const backdrop = document.createElement('div'); backdrop.className = 'cdk-overlay-backdrop'; backdrop.onclick = close;
        const pane = document.createElement('div'); pane.className = 'cdk-overlay-pane';
        document.body.append(backdrop, pane); return pane;
    }
    const current = document.querySelector('ms-model-selector-v3');
    current.onclick = () => {
        const pane = overlay();
        for (const [id, label] of [['gemini-3.8-flash','Gemini 3.8 Flash'], ['gemini-3.1-pro','Gemini 3.1 Pro']]) {
            const option = document.createElement('button'); option.setAttribute('role', 'option');
            option.innerHTML = `<span class="model-name">${label}</span><span>${id}</span>`;
            option.onclick = () => {
                current.querySelector('.title').textContent = label;
                current.querySelector('.subtitle').textContent = id;
                document.querySelector('.mat-mdc-select-min-line').textContent = id.endsWith('pro') ? 'High' : 'Medium';
                close();
            };
            pane.append(option);
        }
    };
    document.querySelector('mat-select').onclick = () => {
        const pane = overlay();
        const names = current.textContent.includes('gemini-3.1-pro') ? ['Low','High'] : ['Minimal','Low','Medium','High'];
        for (const name of names) {
            const option = document.createElement('mat-option'); option.setAttribute('role', 'option'); option.textContent = name;
            option.onclick = () => { document.querySelector('.mat-mdc-select-min-line').textContent = name; close(); };
            pane.append(option);
        }
    };
    const input = document.querySelector('textarea');
    const run = document.querySelector('[aria-label="Run"]');
    input.addEventListener('input', () => { run.disabled = !input.value.trim(); });
    run.onclick = () => {
        clicks++;
        const turn = document.createElement('ms-chat-turn');
        turn.textContent = input.value; document.querySelector('#turns').append(turn);
        input.value = ''; run.disabled = true;
    };
    function call(action, data = {}) {
        return JSON.parse(vm.runInContext(source.replace('__HOME_REQUEST__', JSON.stringify({...data, action})), context));
    }
    function settings() { call('settings'); }
    const request = {id:'submission-1', model:'gemini-3.8-flash', thinking:'medium', temporary, draft:'你好\n"保留草稿" <tag> \\ 中文'};
    return {call, settings, request, document, input, run, window: pageWindow, clicks: () => clicks};
}

test('adapter only operates in the exact AI Studio main frame', () => {
    assert.equal(workspace({origin:'https://aistudio.google.com.evil.example'}).call('status').status, 'unsupported');
    assert.equal(workspace({frame:true}).call('status').status, 'unsupported');
});

test('requires the actual prompt editor, not an unrelated search box', () => {
    const w = workspace();
    assert.equal(w.call('status').status, 'ready');
    w.document.querySelector('ms-prompt-input-wrapper').remove();
    w.document.body.innerHTML += '<textarea placeholder="Search models"></textarea>';
    assert.equal(w.call('status').status, 'waiting');
});

test('discovers model choices from the website and confirms a changed model', () => {
    const w = workspace(); w.settings();
    assert.equal(w.call('models').status, 'waiting');
    const catalog = w.call('models');
    assert.deepEqual(catalog.models.map(e => e.id), ['gemini-3.8-flash','gemini-3.1-pro']);
    assert.equal(w.call('selectModel',{model:'gemini-3.1-pro'}).status,'waiting');
    w.call('selectModel',{model:'gemini-3.1-pro'});
    assert.equal(w.call('selectModel',{model:'gemini-3.1-pro'}).status,'ready');
    assert.equal(w.call('status').model.label,'Gemini 3.1 Pro');
});

test('thinking options change with the selected model and unsupported levels fail', () => {
    const w = workspace(); w.settings();
    w.call('thinkingOptions');
    assert.deepEqual(w.call('thinkingOptions').options.map(e => e.id), ['minimal','low','medium','high']);
    w.call('selectModel',{model:'gemini-3.1-pro'}); w.call('selectModel',{model:'gemini-3.1-pro'});
    w.call('thinkingOptions');
    assert.deepEqual(w.call('thinkingOptions').options.map(e => e.id), ['low','high']);
    w.call('selectThinking',{thinking:'medium'});
    assert.equal(w.call('selectThinking',{thinking:'medium'}).reason,'thinking_unavailable');
});

test('fills exact Unicode/multiline draft, sends once, and waits for a visible chat receipt', () => {
    const w = workspace(); w.settings();
    assert.equal(w.call('fill',w.request).status,'ready');
    assert.equal(w.input.value,w.request.draft);
    assert.equal(w.call('receipt',{id:w.request.id}).status,'unknown');
    assert.equal(w.call('commit',{id:w.request.id}).status,'dispatched');
    assert.equal(w.call('commit',{id:w.request.id}).status,'dispatched');
    assert.equal(w.clicks(),1);
    assert.equal(w.call('receipt',{id:w.request.id}).status,'confirmed');
    assert.equal(w.call('commit',{id:w.request.id}).status,'confirmed');
    assert.equal(w.clicks(),1);
});

test('wrong model or thinking never fills or sends the draft', () => {
    const w = workspace(); w.settings();
    assert.equal(w.call('fill',{...w.request,model:'gemini-does-not-exist'}).reason,'model_mismatch');
    assert.equal(w.call('fill',{...w.request,thinking:'high'}).reason,'thinking_mismatch');
    assert.equal(w.input.value,''); assert.equal(w.clicks(),0);
});

test('temporary URL alone is insufficient; real temporary indicator is required', () => {
    const missing = workspace({temporary:true,indicator:false}); missing.settings();
    assert.equal(missing.call('fill',missing.request).reason,'temporary_unconfirmed');
    assert.equal(missing.clicks(),0);
    const actual = workspace({temporary:true}); actual.settings();
    assert.equal(actual.call('fill',actual.request).status,'ready');
    assert.equal(actual.call('commit',{id:actual.request.id}).status,'dispatched');
    assert.equal(actual.clicks(),1);
});

test('temporary mode can be applied through the actual hidden menu control', () => {
    const w = workspace();
    const more = w.document.createElement('button'); more.setAttribute('aria-label', 'More options');
    w.document.querySelector('header').append(more);
    let toggles = 0;
    more.onclick = () => {
        const menu = w.document.createElement('div'); menu.setAttribute('role', 'menu');
        const item = w.document.createElement('button'); item.setAttribute('role', 'menuitemcheckbox');
        item.setAttribute('aria-checked', 'false');
        item.innerHTML = '<i>visibility_off</i><span class="mat-mdc-menu-item-text">Temporary chat</span>';
        item.onclick = () => { toggles++; w.document.querySelector('h1').textContent = 'Temporary chat'; menu.remove(); };
        menu.append(item); w.document.body.append(menu);
    };
    assert.equal(w.call('selectTemporary', {temporary: true}).status, 'waiting');
    assert.equal(w.call('selectTemporary', {temporary: true}).status, 'waiting');
    assert.equal(w.call('selectTemporary', {temporary: true}).status, 'ready');
    assert.equal(w.call('selectTemporary', {temporary: true}).status, 'ready');
    assert.equal(toggles, 1);
});

test('a slow model picker is opened once rather than repeatedly toggled closed', () => {
    const w = workspace(); w.settings();
    let opens = 0; w.document.querySelector('ms-model-selector-v3').onclick = () => { opens++; };
    for (let i=0;i<8;i++) assert.equal(w.call('models').status, 'waiting');
    assert.equal(opens, 1);
});

test('does not overwrite an existing website draft', () => {
    const w = workspace(); w.settings(); w.input.value = '未完成的网页草稿';
    assert.equal(w.call('fill',w.request).reason,'existing_draft');
    assert.equal(w.input.value,'未完成的网页草稿');
});

test('disabled Run never counts as a sent submission', () => {
    const w = workspace(); w.settings(); w.call('fill',w.request); w.run.disabled = true;
    assert.equal(w.call('commit',{id:w.request.id}).status,'waiting');
    assert.equal(w.window.__geminiHome.transaction.clicked,false); assert.equal(w.clicks(),0);
});

test('draft or configuration changes between fill and commit abort the submission', () => {
    const w = workspace(); w.settings(); w.call('fill',w.request); w.input.value = 'changed';
    assert.equal(w.call('commit',{id:w.request.id}).reason,'draft_mismatch'); assert.equal(w.clicks(),0);
});

test('a thrown website handler cannot lead to a second click', () => {
    const w = workspace(); w.settings(); w.call('fill',w.request);
    let attempts = 0; w.run.click = () => { attempts++; throw new Error('website failed after click'); };
    assert.equal(w.call('commit',{id:w.request.id}).status,'error');
    assert.equal(w.call('commit',{id:w.request.id}).status,'dispatched'); assert.equal(attempts,1);
});

test('clearing the editor alone is not a send receipt; navigation never replays', () => {
    const w = workspace(); w.settings(); w.call('fill',w.request);
    w.run.onclick = () => { w.input.value = ''; };
    w.call('commit',{id:w.request.id});
    assert.equal(w.call('receipt',{id:w.request.id}).status,'dispatched');
    delete w.window.__geminiHome;
    assert.equal(w.call('receipt',{id:w.request.id}).status,'unknown');
    assert.equal(w.call('commit',{id:w.request.id}).reason,'missing_transaction');
});
