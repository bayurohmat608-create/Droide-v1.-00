import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(new URL('../src/index.js', import.meta.url), 'utf8');
const worker = (await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'))).default;
const env = { GITHUB_CLIENT_ID: 'test-client', GITHUB_CLIENT_SECRET: 'test-secret' };
const endpoint = 'https://relay.example/oauth/github/exchange';
const form = new URLSearchParams({ client_id: env.GITHUB_CLIENT_ID, code: 'code', code_verifier: 'v'.repeat(43), redirect_uri: 'com.baystudio.droide.oauth://github/callback' });
function request(body, headers = {}) {
  return new Request(endpoint, { method: 'POST', body, duplex: 'half', headers: { 'content-type': 'application/x-www-form-urlencoded', ...headers } });
}
async function fakeFetch(callback) {
  const previous = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => { calls++; return new Response(JSON.stringify({ access_token: 'token'.repeat(10) }), { headers: { 'content-type': 'application/json' } }); };
  try { await callback(() => calls); } finally { globalThis.fetch = previous; }
}
test('valid PKCE exchange succeeds', async () => fakeFetch(async calls => {
  const response = await worker.fetch(request(form.toString()), env);
  assert.equal(response.status, 200); assert.equal(calls(), 1);
}));
test('spoofed content length cannot permit oversized streamed input', async () => fakeFetch(async calls => {
  let canceled = false;
  const body = new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array(8193)); }, cancel() { canceled = true; } });
  const response = await worker.fetch(request(body, { 'content-length': '1' }), env);
  assert.equal(response.status, 413); assert.equal(calls(), 0); assert.equal(canceled, true);
}));
test('limit counts UTF8 bytes rather than characters', async () => fakeFetch(async calls => {
  const response = await worker.fetch(request('界'.repeat(3000)), env);
  assert.equal(response.status, 413); assert.equal(calls(), 0);
}));
test('lookalike media type is rejected', async () => fakeFetch(async calls => {
  const response = await worker.fetch(request(form.toString(), { 'content-type': 'application/x-www-form-urlencoded-attacker' }), env);
  assert.equal(response.status, 415); assert.equal(calls(), 0);
}));
test('oversized upstream body is canceled and rejected', async () => {
  const previous = globalThis.fetch;
  let canceled = false;
  globalThis.fetch = async () => new Response(new ReadableStream({ start(controller) { controller.enqueue(new Uint8Array(65537)); }, cancel() { canceled = true; } }));
  try { const response = await worker.fetch(request(form.toString()), env); assert.equal(response.status, 502); assert.equal(canceled, true); }
  finally { globalThis.fetch = previous; }
});
