import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadComponent } from './componentHarness.mjs';

const textOf = (node) => Array.isArray(node) ? node.map(textOf).join(' ')
  : node && typeof node === 'object' ? textOf(node.props?.children) : String(node ?? '');

async function renderPricing(summary = {}) {
  const component = await loadComponent('pages/airline/Dashboard/FlightManagment/FlightCard.jsx');
  return textOf(component.render({ flight: { id: 1, flightNumber: 'VN1001', ...summary } }));
}

test('missing summary pricing does not claim fares are unconfigured', async () => {
  // Flight listings omit lowestPrice even when the separate Fares tab has fares.
  for (const summary of [{}, { lowestPrice: null }]) {
    const text = await renderPricing(summary);
    assert.doesNotMatch(text, /Not set|Configure pricing/);
    assert.match(text, /Pricing unavailable/);
    assert.match(text, /View fares in flight details/);
  }
});

test('supplied starting fares include a valid zero price', async () => {
  for (const lowestPrice of [139, 0]) {
    const text = await renderPricing({ lowestPrice });
    assert.ok(text.replace(/\s/g, '').includes(`$${lowestPrice.toLocaleString()}`));
    assert.match(text, /Starting fare/);
    assert.doesNotMatch(text, /Pricing unavailable|Not set|Configure pricing/);
  }
});

test('invalid summary prices are unavailable', async () => {
  for (const lowestPrice of [-1, NaN, Infinity, '', '139']) {
    const text = await renderPricing({ lowestPrice });
    assert.match(text, /Pricing unavailable/);
    assert.doesNotMatch(text, /Starting fare/);
  }
});
