import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadComponent, findAll } from './componentHarness.mjs';

const textOf = (node) => Array.isArray(node) ? node.map(textOf).join(' ')
  : node && typeof node === 'object' ? textOf(node.props?.children) : String(node ?? '');

test('Create New Flight shortcut navigates to the creation route without form-state callbacks', async () => {
  const destinations = [];
  const component = await loadComponent('pages/airline/Dashboard/DashboardOverview.jsx', {
    useSelector: () => ({ flights: [] }),
    useNavigate: () => (path) => destinations.push(path),
  });
  const button = findAll(component.render(), (node) => node.type === 'Button'
    && textOf(node).includes('Create New Flight'))[0];
  assert.ok(button, 'Create New Flight shortcut must be present');
  button.props.onClick();
  assert.deepEqual(destinations, ['/airline/flights/new']);
});
