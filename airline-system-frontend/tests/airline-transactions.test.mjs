import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createServer } from 'vite';
import { loadComponent, findAll } from './componentHarness.mjs';

const textOf = node => Array.isArray(node) ? node.map(textOf).join(' ')
  : node && typeof node === 'object' ? textOf(node.props?.children) : String(node ?? '');
const transaction = { id: 1, bookingId: 20, transactionId: 'TX-OWNER', gateway: 'STRIPE',
  amount: 25, status: 'SUCCESS', createdAt: '2026-10-16T10:00:00' };
const result = { content: [transaction], totalElements: 25, totalPages: 2, number: 0,
  summary: { totalRevenue: 1250, successfulPayments: 22, pendingPayments: 2, failedPayments: 1, successRate: 88 } };
async function page(state = { airlineTransactions: result }) {
  const requests = [];
  const dispatch = (args) => { requests.push(args); return { abort() {} }; };
  const component = await loadComponent('pages/airline/Dashboard/Transactions/TransactionsManagement.jsx', {
    useDispatch: () => dispatch, useSelector: () => state, getAirlinePayments: args => args,
  });
  return { component, requests };
}
const button = (tree, label) => findAll(tree, node => node.type === 'Button' && textOf(node).trim() === label)[0];

test('owner transactions show server totals and scoped rows, and details open and close', async () => {
  const { component, requests } = await page();
  let tree = component.render();
  component.flushEffects();
  assert.equal(requests.length, 1);
  assert.equal(requests[0].page, 0);
  assert.ok(textOf(tree).includes('1250'));
  assert.match(textOf(tree), /Matching Transactions \( 25 \)/);
  assert.ok(textOf(tree).includes('TX-OWNER'));
  const view = findAll(tree, node => node.type === 'Button' && findAll(node, child => child.type === 'Eye').length)[0];
  view.props.onClick();
  tree = component.render();
  assert.match(textOf(tree), /Transaction Details/);
  button(tree, '✕').props.onClick();
  assert.ok(!textOf(component.render()).includes('Transaction Details'));
});

test('pagination, search, filters, sorting, and refresh request server data', async () => {
  const { component, requests } = await page();
  let tree = component.render(); component.flushEffects();
  button(tree, 'Next').props.onClick();
  tree = component.render(); component.flushEffects();
  assert.equal(requests.at(-1).page, 1);
  findAll(tree, node => node.type === 'Select')[0].props.onValueChange('SUCCESS');
  tree = component.render(); component.flushEffects();
  assert.equal(requests.at(-1).page, 0);
  assert.equal(requests.at(-1).status, 'SUCCESS');
  findAll(tree, node => node.type === 'Select')[1].props.onValueChange('STRIPE');
  tree = component.render(); component.flushEffects();
  assert.equal(requests.at(-1).gateway, 'STRIPE');
  findAll(tree, node => node.type === 'Input')[0].props.onChange({ target: { value: 'pay-2' } });
  tree = component.render(); component.flushEffects();
  assert.equal(requests.at(-1).search, 'pay-2');
  findAll(tree, node => node.type === 'button' && textOf(node).includes('Amount'))[0].props.onClick();
  tree = component.render(); component.flushEffects();
  assert.equal(requests.at(-1).sortBy, 'amount');
  button(tree, 'Refresh').props.onClick();
  assert.equal(requests.at(-1).search, 'pay-2');
  assert.equal(requests.at(-1).status, 'SUCCESS');
});

test('loading and request failures do not masquerade as an empty transaction list', async () => {
  const loading = (await page({ airlineTransactionsLoading: true })).component.render();
  assert.match(textOf(loading), /Loading transactions/);
  assert.ok(!textOf(loading).includes('No transactions found'));
  const { component, requests } = await page({ airlineTransactionsError: 'Service unavailable' });
  const error = component.render();
  assert.match(textOf(error), /Error loading transactions.*Service unavailable/);
  button(error, 'Retry').props.onClick();
  assert.equal(requests.length, 1);
  assert.match(textOf((await page({ airlineTransactions: { ...result, content: [], totalElements: 0 } })).component.render()), /No transactions found/);
});

test('owner reducer ignores stale responses and preserves separate admin data', async () => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom',
    optimizeDeps: { noDiscovery: true, include: [] } });
  try {
    const { default: reducer } = await server.ssrLoadModule('/src/Redux/payment/paymentSlice.js');
    let state = reducer(undefined, { type: '@@init' });
    state = { ...state, payments: [{ id: 'admin-only' }] };
    const action = (phase, requestId, payload) => ({ type: `payment/getAirline/${phase}`, payload, meta: { requestId } });
    state = reducer(state, action('pending', 'old'));
    state = reducer(state, action('pending', 'new'));
    state = reducer(state, action('fulfilled', 'old', { content: [{ id: 'stale' }] }));
    assert.equal(state.airlineTransactions, null);
    assert.equal(state.airlineTransactionsLoading, true);
    state = reducer(state, action('fulfilled', 'new', result));
    assert.deepEqual(state.airlineTransactions, result);
    assert.deepEqual(state.payments, [{ id: 'admin-only' }]);
    state = reducer(state, action('pending', 'retry'));
    state = reducer(state, action('rejected', 'retry', 'Unavailable'));
    assert.equal(state.airlineTransactionsError, 'Unavailable');
    assert.equal(state.airlineTransactionsLoading, false);
  } finally { await server.close(); }
});

test('owner thunk calls booking-scoped endpoint with encoded search and filters', async () => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom',
    optimizeDeps: { noDiscovery: true, include: [] } });
  const originalStorage = globalThis.localStorage;
  globalThis.localStorage = { getItem: () => null };
  try {
    const { getAirlinePayments } = await server.ssrLoadModule('/src/Redux/payment/paymentThunk.js');
    const { default: api } = await server.ssrLoadModule('/src/utils/api.js');
    let called;
    api.get = async (url, options) => { called = { url, options }; return { data: result }; };
    const response = await getAirlinePayments({ page: 1, size: 10, search: ' pay & id ', status: 'SUCCESS', gateway: 'STRIPE' })(() => {}, () => ({}), undefined);
    assert.equal(response.meta.requestStatus, 'fulfilled');
    const url = new URL(called.url, 'http://fixture.test');
    assert.equal(url.pathname, '/api/bookings/transactions/airline');
    assert.equal(url.searchParams.get('search'), 'pay & id');
    assert.equal(url.searchParams.get('page'), '1');
    assert.equal(url.searchParams.get('status'), 'SUCCESS');
    assert.equal(url.searchParams.get('gateway'), 'STRIPE');
    assert.ok(called.options.signal);
  } finally { globalThis.localStorage = originalStorage; await server.close(); }
});
