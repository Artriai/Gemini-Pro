import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { test } from 'node:test';
import assert from 'node:assert/strict';

const source = readFileSync(new URL('../app/src/main/assets/blob_download.js', import.meta.url), 'utf8');
async function transfer(blob, { failFetch = false, rejectChunk = false } = {}) {
  const messages = [];
  let inFlight = 0;
  let maxInFlight = 0;
  const channel = {
    postMessage(raw) {
      const message = JSON.parse(raw);
      messages.push(message);
      maxInFlight = Math.max(maxInFlight, ++inFlight);
      queueMicrotask(() => {
        inFlight--;
        // A reply from an obsolete transfer must not advance this transfer.
        this.onmessage({ data: JSON.stringify({ token: 'old-token', ok: true }) });
        this.onmessage({ data: JSON.stringify({ token: message.token, ok: !(rejectChunk && message.type === 'chunk') }) });
      });
    },
  };
  const sandbox = {
    window: { GeminiDownload: channel },
    fetch: async () => {
      if (failFetch) throw new Error('offline');
      return { ok: true, blob: async () => blob };
    },
    Uint8Array, setTimeout, clearTimeout,
    btoa: value => Buffer.from(value, 'binary').toString('base64'),
  };
  runInNewContext(source, sandbox);
  await sandbox.downloadGeminiBlob('blob:https://aistudio.google.com/id', 'test-token', 256 * 1024 * 1024);
  assert.equal(channel.onmessage, null);
  return { messages, maxInFlight };
}

test('downloads all bytes with bounded chunks and acknowledgment backpressure', async () => {
  const bytes = Buffer.alloc(140000);
  for (let i = 0; i < bytes.length; i++) bytes[i] = i % 251;
  const { messages, maxInFlight } = await transfer(new Blob([bytes], { type: 'video/mp4' }));
  assert.equal(messages[0].type, 'begin');
  assert.equal(messages[0].size, bytes.length);
  assert.equal(messages.at(-1).type, 'end');
  const chunks = messages.filter(message => message.type === 'chunk').map(message => Buffer.from(message.data, 'base64'));
  assert.equal(chunks.length, 3);
  assert(chunks.every(chunk => chunk.length <= 49152));
  assert.deepEqual(Buffer.concat(chunks), bytes);
  assert.equal(maxInFlight, 1);
});

test('failed fetch cleans up through abort', async () => {
  const { messages } = await transfer(null, { failFetch: true });
  assert.deepEqual(messages.map(message => message.type), ['abort']);
});

test('oversized file is rejected before reading chunks', async () => {
  const { messages } = await transfer({ size: 256 * 1024 * 1024 + 1, slice() { throw new Error('must not read'); } });
  assert.deepEqual(messages.map(message => message.type), ['abort']);
});

test('native rejection stops further chunks and never publishes partial file', async () => {
  const { messages } = await transfer(new Blob([Buffer.alloc(100000)]), { rejectChunk: true });
  assert.deepEqual(messages.map(message => message.type), ['begin', 'chunk', 'abort']);
});
