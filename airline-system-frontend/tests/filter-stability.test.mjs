import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadComponent, findAll } from './componentHarness.mjs';

test('filter sections retain their component identity throughout a search update', async () => {
  const component = await loadComponent('pages/traveler/FlightList/Filterssidebar.jsx');
  let filters = {
    airlines: [], priceRange: { min: 1000, max: 200000 },
    departureTimeRange: 'any', arrivalTimeRange: 'any',
  };
  const props = () => ({ filters, airlines: [], onFiltersChange(update) {
    filters = typeof update === 'function' ? update(filters) : update;
  } });
  const sections = tree => findAll(tree, node => typeof node.type === 'function' && node.props.id);
  const initial = sections(component.render(props()));
  assert.equal(initial.length, 4);
  const departure = initial.find(node => node.props.id === 'departure');
  findAll(departure, node => node.type === 'RadioGroup')[0].props.onValueChange('morning');
  assert.equal(filters.departureTimeRange, 'morning');

  // The filter change, request pending and request completion each render the sidebar.
  // A new component type forces React to remount the animated section and its controls.
  for (const phase of ['filter change', 'request pending', 'request completed']) {
    const current = sections(component.render(props()));
    for (let index = 0; index < initial.length; index++) {
      assert.equal(current[index].type, initial[index].type,
        `${phase}: ${initial[index].props.id} section must not remount and replay its animation`);
    }
  }
});
