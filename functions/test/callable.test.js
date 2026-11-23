const { test } = require('node:test');
const assert = require('node:assert/strict');
const { randomUUID } = require('node:crypto');
const { setTimeout: delay } = require('node:timers/promises');

test('callable validates Auth emulator tokens and cleanup trigger completes a purge', {
  skip: !process.env.FIREBASE_AUTH_EMULATOR_HOST,
  timeout: 45000
}, async () => {
  const project = 'demo-todolist';
  const signup = await fetch('http://' + process.env.FIREBASE_AUTH_EMULATOR_HOST +
    '/identitytoolkit.googleapis.com/v1/accounts:signUp?key=local-test', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email: randomUUID() + '@example.test', password: 'test-only-password', returnSecureToken: true })
  });
  assert.equal(signup.status, 200);
  const { localId: uid, idToken } = await signup.json();
  const cid = randomUUID();
  async function call(kind, fields = {}, token = idToken) {
    const response = await fetch('http://127.0.0.1:5001/' + project + '/asia-east1/workspace', {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...(token && { authorization: 'Bearer ' + token }) },
      body: JSON.stringify({ data: {
        kind, category_id: cid, expected_uid: uid, operation_id: randomUUID(), client_occurred_at: Date.now(), ...fields
      } })
    });
    return { status: response.status, body: await response.json() };
  }
  assert.equal((await call('LIST', {}, '')).status, 401);
  assert.equal((await call('PROFILE')).body.result.ok, true);
  assert.equal((await call('CREATE', { entity_type: 'CATEGORY', base_revision: 0, payload: { name: 'Callable list' } })).status, 200);
  assert.equal((await call('TRASH', { entity_type: 'CATEGORY', base_revision: 1 })).status, 200);
  const purge = { entity_type: 'CATEGORY', base_revision: 2, operation_id: randomUUID(), client_occurred_at: Date.now() };
  const started = await call('PURGE', purge);
  assert.equal(started.status, 200, JSON.stringify(started.body));
  assert.equal(started.body.result.pending, true);
  let result;
  for (let index = 0; index < 60; index++) {
    result = await call('PURGE', purge);
    if (result.body.result?.ok) break;
    await delay(250);
  }
  assert.equal(result.body.result?.ok, true, JSON.stringify(result.body));
});
