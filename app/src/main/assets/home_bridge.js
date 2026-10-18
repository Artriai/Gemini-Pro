/* UI adapter only: uses AI Studio's own controls, never private APIs or credentials. */
(function (request) {
    'use strict';
    if (location.origin !== 'https://aistudio.google.com' || window.top !== window) {
        return JSON.stringify({status: 'unsupported'});
    }
    const clean = value => String(value || '').replace(/\s+/g, ' ').trim();
    const text = element => clean(element && (element.innerText || element.textContent));
    const all = (selector, root = document) => Array.from(root.querySelectorAll(selector));
    const visible = element => {
        if (!element || element.closest('[hidden], [aria-hidden="true"]')) return false;
        const rect = element.getBoundingClientRect(), style = getComputedStyle(element);
        return rect.width > 0 && rect.height > 0 && style.display !== 'none' &&
            style.visibility !== 'hidden' && style.opacity !== '0';
    };
    const first = (selector, root = document) => all(selector, root).find(visible);
    const enabled = element => element && !element.disabled && element.getAttribute('aria-disabled') !== 'true';
    const label = element => clean(element.getAttribute('aria-label') || element.getAttribute('title') ||
        text(element.querySelector('.mat-mdc-menu-item-text, .mdc-list-item__primary-text') || element));
    const buttons = (root = document) => all('button, [role="button"], [role="menuitem"]', root).filter(visible);
    const findButton = (pattern, root = document) => buttons(root).find(e => enabled(e) && pattern.test(label(e)));
    const key = value => clean(value).toLowerCase().replace(/^models\//, '').replace(/[\s_]+/g, '-');
    const state = window.__geminiHome || (window.__geminiHome = {
        documentId: String(Date.now()) + '-' + Math.random().toString(36).slice(2), transaction: null,
    });
    const modelSelector = () => first('ms-model-selector-v3, ms-model-selector-two-column, ms-model-selector, .settings-model-selector');
    const thinkingSelector = () => first('ms-thinking-level-setting, [data-test-id="thinkingLevel"], [data-test-id="thinkingLevelSetting"]');
    const editor = () => first('ms-prompt-input-wrapper textarea, ms-prompt-box textarea, ms-autosize-textarea textarea, textarea[aria-label="Type something"], textarea[aria-label*="prompt" i], textarea[placeholder*="prompt" i], ms-prompt-input-wrapper [contenteditable="true"]');
    const editorValue = element => element ? (element.isContentEditable || element.getAttribute('contenteditable') === 'true' ? element.textContent : element.value) || '' : '';
    const turns = () => all('ms-chat-turn').filter(e => text(e).length > 0);
    function parseModel(element) {
        if (!element) return null;
        const content = text(element);
        const idNode = element.matches('[data-model-id], [data-model-name]') ? element : element.querySelector('[data-model-id], [data-model-name]');
        const rawId = idNode && (idNode.getAttribute('data-model-id') || idNode.getAttribute('data-model-name'));
        const slug = clean(rawId).replace(/^models\//, '') || (content.match(/gemini-[a-z0-9][a-z0-9._-]*/i) || [])[0];
        const titleNode = element.querySelector('[data-test-id="model-name"], .model-name, .model-title, .title, h3');
        const title = text(titleNode) || (content.match(/Gemini\s+[\d.]+\s+(?:Flash(?:[ -]Lite)?|Pro)(?:\s+Preview)?/i) || [])[0] || slug;
        if (!title || (!slug && !/^Gemini /i.test(title))) return null;
        return {id: slug || title, label: title};
    }
    function currentModel() { return parseModel(modelSelector()); }
    function sameModel(model, desired) { return !!model && (key(model.id) === key(desired) || key(model.label) === key(desired)); }
    function thinkingControl() {
        const host = thinkingSelector();
        return host && (first('mat-select, select, [role="combobox"], button', host) || host);
    }
    function currentThinking() {
        const control = thinkingControl();
        if (!control) return '';
        const selected = control.querySelector('.mat-mdc-select-min-line, .mat-mdc-select-value-text, .mat-select-value-text, [data-test-id="selected-value"]');
        return key(selected ? text(selected) : control.tagName === 'SELECT' ? control.options[control.selectedIndex]?.text : text(control));
    }
    function temporaryState() {
        const toggle = first('[data-test-id="temporary-chat-toggle"], [aria-label="Temporary chat"][role="switch"], [aria-label="临时对话"][role="switch"]');
        if (toggle) return toggle.getAttribute('aria-checked') === 'true';
        const indicator = first('ms-temporary-chat-indicator, .temporary-chat-label, [data-test-id="temporary-chat-indicator"]');
        if (indicator) return true;
        const heading = all('h1, h2, ms-prompt-title, .prompt-title, .chat-title, header *, ms-prompt-toolbar *, ms-prompt-header *, ms-prompt-name *, span')
            .find(e => visible(e) && !e.closest('[role="menu"], [role="menuitem"], [role="menuitemcheckbox"], .cdk-overlay-pane') &&
                e.getBoundingClientRect().top < 180 && /^(temporary chat|临时对话|临时聊天)$/i.test(label(e)));
        if (heading) return true;
        // A requested temporary URL alone is not evidence that the privacy mode took effect.
        return new URL(location.href).searchParams.get('temporary') === 'true' ? null : false;
    }
    function snapshot() {
        const model = currentModel();
        return {status: editor() ? 'ready' : 'waiting', documentId: state.documentId,
            model, thinking: currentThinking(), temporary: temporaryState(), turns: turns().length,
            hasDraft: !!clean(editorValue(editor()))};
    }
    function openSettings() {
        if (modelSelector()) return true;
        const button = first('button.runsettings-toggle-button') || findButton(/^(run settings|show run settings|open run settings|settings|运行设置|打开运行设置)$/i) ||
            buttons().find(e => enabled(e) && /^(tune|settings)$/i.test(text(e)) && !e.closest('[role="dialog"]'));
        if (button) button.click();
        return false;
    }
    function dismissOverlay() {
        const backdrops = all('.cdk-overlay-backdrop').filter(visible);
        if (backdrops.length) backdrops[backdrops.length - 1].click();
        else document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', code: 'Escape', bubbles: true}));
    }
    function closePanels() {
        dismissOverlay();
        state.modelOpening = false;
        state.thinkingOpening = false;
        state.temporaryOpening = false;
        state.temporaryClicked = false;
        const close = findButton(/^(close run settings(?: panel)?|close settings|close panel|关闭运行设置|关闭设置)$/i) ||
            (first('ms-right-side-panel') && findButton(/^(close|关闭)$/i, first('ms-right-side-panel')));
        if (close) close.click();
    }
    function modelOptions() {
        const candidates = all('[role="option"], ms-model-selector-dialog button, ms-model-selector-v3-dialog button, .cdk-overlay-pane button, .cdk-overlay-pane [role="button"], .cdk-overlay-pane .model-card, .cdk-overlay-pane .model-item, .cdk-overlay-pane [data-model-id]');
        const seen = new Set();
        return candidates.filter(e => visible(e) && enabled(e)).map(element => ({element, model: parseModel(element)}))
            .filter(item => item.model && !seen.has(item.model.id) && seen.add(item.model.id));
    }
    function openModels() {
        if (!openSettings()) return;
        if (state.modelOpening) return;
        const host = modelSelector();
        const control = first('button, [role="combobox"], [role="button"]', host) || host;
        if (enabled(control)) { state.modelOpening = true; control.click(); }
    }
    function openThinking(control) {
        if (!state.thinkingOpening) { state.thinkingOpening = true; control.click(); }
    }
    function thinkingOptions() {
        return all('[role="option"], mat-option').filter(e => visible(e) && enabled(e)).map(element => {
            const title = text(element.querySelector('.mdc-list-item__primary-text') || element);
            return {element, id: key(title), label: title};
        })
            .filter(item => item.id && !parseModel(item.element));
    }
    function verify(config) {
        if (!sameModel(currentModel(), config.model)) return 'model_mismatch';
        if (config.thinking && currentThinking() !== config.thinking) return 'thinking_mismatch';
        if (temporaryState() !== config.temporary) return 'temporary_unconfirmed';
        return null;
    }
    function runButton() {
        return first('ms-run-button button, button[aria-label="Run"], button[aria-label="Run prompt"], button[data-test-id="run-button"]') ||
            findButton(/^(Run|Run prompt|发送|运行)(?:\s+(?:Ctrl|⌘|Enter).*)?$/i);
    }
    try {
        switch (request.action) {
            case 'status': return JSON.stringify(snapshot());
            case 'settings': return JSON.stringify({status: openSettings() ? 'ready' : 'waiting'});
            case 'models': {
                const options = modelOptions();
                if (!options.length) { openModels(); return JSON.stringify({status: 'waiting'}); }
                const models = options.map(item => item.model);
                state.modelOpening = false;
                dismissOverlay();
                return JSON.stringify({status: 'ready', models});
            }
            case 'selectModel': {
                if (sameModel(currentModel(), request.model)) { state.modelOpening = false; return JSON.stringify({status: 'ready'}); }
                const options = modelOptions();
                const target = options.find(item => sameModel(item.model, request.model));
                if (target) { state.modelOpening = false; target.element.click(); }
                else if (!options.length) openModels();
                else return JSON.stringify({status: 'error', reason: 'model_unavailable'});
                return JSON.stringify({status: 'waiting'});
            }
            case 'thinkingOptions': {
                if (!openSettings()) return JSON.stringify({status: 'waiting'});
                const control = thinkingControl();
                if (!control) return JSON.stringify({status: 'ready', options: []});
                if (control.tagName === 'SELECT') {
                    return JSON.stringify({status: 'ready', options: Array.from(control.options).filter(e => !e.disabled).map(e => ({id: key(e.text), label: e.text}))});
                }
                const options = thinkingOptions();
                if (!options.length) { openThinking(control); return JSON.stringify({status: 'waiting'}); }
                state.thinkingOpening = false;
                dismissOverlay();
                return JSON.stringify({status: 'ready', options: options.map(({id, label}) => ({id, label}))});
            }
            case 'selectThinking': {
                if (!request.thinking || currentThinking() === request.thinking) { state.thinkingOpening = false; return JSON.stringify({status: 'ready'}); }
                if (!openSettings()) return JSON.stringify({status: 'waiting'});
                const control = thinkingControl();
                if (!control) return JSON.stringify({status: 'error', reason: 'thinking_unavailable'});
                if (control.tagName === 'SELECT') {
                    const option = Array.from(control.options).find(e => key(e.text) === request.thinking && !e.disabled);
                    if (!option) return JSON.stringify({status: 'error', reason: 'thinking_unavailable'});
                    control.value = option.value;
                    control.dispatchEvent(new Event('change', {bubbles: true}));
                } else {
                    const options = thinkingOptions(), target = options.find(e => e.id === request.thinking);
                    if (target) { state.thinkingOpening = false; target.element.click(); }
                    else if (!options.length) openThinking(control);
                    else return JSON.stringify({status: 'error', reason: 'thinking_unavailable'});
                }
                return JSON.stringify({status: 'waiting'});
            }
            case 'selectTemporary': {
                if (temporaryState() === request.temporary) {
                    if (state.temporaryOpening || state.temporaryClicked) dismissOverlay();
                    state.temporaryOpening = false; state.temporaryClicked = false;
                    return JSON.stringify({status: 'ready'});
                }
                if (turns().length) return JSON.stringify({status: 'error', reason: 'existing_conversation'});
                const confirmation = findButton(/^(start temporary chat|turn on temporary chat|enable temporary chat|开始临时对话|开启临时对话)$/i,
                    first('[role="dialog"]') || document);
                if (request.temporary && confirmation) { confirmation.click(); return JSON.stringify({status: 'waiting'}); }
                if (state.temporaryClicked) return JSON.stringify({status: 'waiting'});
                const item = all('[role="menuitem"], [role="menuitemcheckbox"]').find(e => visible(e) && enabled(e) &&
                    /^(temporary chat|turn (?:on|off) temporary chat|disable temporary chat|临时对话|临时聊天|关闭临时对话)$/i.test(label(e)));
                if (item) {
                    const checked = item.getAttribute('aria-checked');
                    const known = checked === 'true' ? true : checked === 'false' ? false : temporaryState();
                    if (known === null) return JSON.stringify({status: 'error', reason: 'temporary_unconfirmed'});
                    if (known !== request.temporary) { state.temporaryClicked = true; item.click(); }
                    return JSON.stringify({status: 'waiting'});
                }
                if (!state.temporaryOpening) {
                    closePanels();
                    const more = findButton(/^(more options|more actions|show more options|more|更多|更多选项)$/i) ||
                        buttons().find(e => /^(more_vert|more_horiz)$/.test(text(e)) && !e.closest('ms-chat-turn'));
                    if (!more) return JSON.stringify({status: 'error', reason: 'temporary_unconfirmed'});
                    state.temporaryOpening = true; more.click();
                }
                return JSON.stringify({status: 'waiting'});
            }
            case 'fill': {
                const failure = verify(request);
                if (failure) return JSON.stringify({status: 'error', reason: failure});
                const input = editor();
                if (!input) return JSON.stringify({status: 'waiting'});
                const existing = editorValue(input);
                if (clean(existing) && existing !== request.draft) return JSON.stringify({status: 'error', reason: 'existing_draft'});
                if (state.transaction?.clicked && state.transaction.id === request.id) return JSON.stringify({status: 'dispatched'});
                if (input.isContentEditable || input.getAttribute('contenteditable') === 'true') input.textContent = request.draft;
                else Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(input, request.draft);
                input.dispatchEvent(new Event('input', {bubbles: true}));
                input.dispatchEvent(new Event('change', {bubbles: true}));
                if (editorValue(input) !== request.draft) return JSON.stringify({status: 'error', reason: 'draft_mismatch'});
                state.transaction = {...request, initialTurns: turns().length, clicked: false, confirmed: false};
                return JSON.stringify({status: 'ready'});
            }
            case 'commit': {
                const tx = state.transaction;
                if (!tx || tx.id !== request.id) return JSON.stringify({status: 'error', reason: 'missing_transaction'});
                if (tx.clicked) return JSON.stringify({status: tx.confirmed ? 'confirmed' : 'dispatched'});
                const failure = verify(tx);
                if (failure) return JSON.stringify({status: 'error', reason: failure});
                if (turns().length !== tx.initialTurns || editorValue(editor()) !== tx.draft) return JSON.stringify({status: 'error', reason: 'draft_mismatch'});
                const button = runButton();
                if (!enabled(button)) return JSON.stringify({status: 'waiting'});
                // Latch BEFORE invoking the website. Even a thrown click cannot be retried automatically.
                tx.clicked = true;
                button.click();
                return JSON.stringify({status: 'dispatched'});
            }
            case 'receipt': {
                const tx = state.transaction;
                if (!tx || tx.id !== request.id || !tx.clicked) return JSON.stringify({status: 'unknown'});
                const newTurns = turns();
                if (tx.confirmed || (newTurns.length > tx.initialTurns && newTurns.slice(tx.initialTurns).some(e => text(e).includes(clean(tx.draft))))) {
                    tx.confirmed = true;
                    tx.draft = '';
                    closePanels();
                    return JSON.stringify({status: 'confirmed'});
                }
                return JSON.stringify({status: 'dispatched'});
            }
            case 'canCommit': return JSON.stringify({status: enabled(runButton()) ? 'ready' : 'waiting'});
            case 'close': closePanels(); return JSON.stringify({status: 'ready'});
            case 'attach': {
                closePanels();
                const button = findButton(/^(add media|add files|insert assets|insert media|upload file|add attachment|添加附件|添加文件|插入素材)$/i);
                if (button) button.click();
                return JSON.stringify({status: button ? 'ready' : 'unsupported'});
            }
            case 'history': {
                closePanels();
                const button = findButton(/^(open navigation menu|open menu|toggle navigation|menu|打开菜单|打开导航菜单)$/i);
                if (button) button.click();
                return JSON.stringify({status: button ? 'ready' : 'unsupported'});
            }
            default: return JSON.stringify({status: 'unsupported'});
        }
    } catch (_) {
        return JSON.stringify({status: 'error', reason: 'layout_changed'});
    }
})(__HOME_REQUEST__);
