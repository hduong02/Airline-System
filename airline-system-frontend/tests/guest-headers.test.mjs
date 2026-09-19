import assert from 'node:assert/strict';
import { test } from 'node:test';
import { getHeaders } from '../src/utils/getHeaders.js';

test('guest requests omit Authorization and signed-in requests include the token', () => {
  const previous = globalThis.localStorage;
  let token = null;
  globalThis.localStorage = { getItem: () => token };
  try {
    assert.deepEqual(getHeaders(), {});
    token = 'fixture-token';
    assert.deepEqual(getHeaders(), { Authorization: 'Bearer fixture-token' });
  } finally { globalThis.localStorage = previous; }
});
