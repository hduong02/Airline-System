import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadComponent, findAll, settle } from './componentHarness.mjs';
import { formatDateOnly } from '../src/utils/dateOnly.js';

const airports = [
  { id: 1, iataCode: 'HAN', name: 'Noi Bai', city: { name: 'Hà Nội' } },
  { id: 2, iataCode: 'SGN', name: 'Tan Son Nhat', city: { name: 'Ho Chi Minh' } },
  { id: 3, iataCode: 'DXB', name: 'Dubai International', city: { name: 'Dubai' } },
];
const searchButton = tree => findAll(tree, n => n.type === 'Button' && findAll(n, c => c.type === 'span' && c.props.children.includes('Search Flights')).length)[0];
async function searchForm(getState = () => ({ airports, loading: false, error: null })) {
  return loadComponent('pages/traveler/Home/FlightSearchBar.jsx', {
    formatDateOnly, listAllAirports: () => ({}), useDispatch: () => () => {}, useSelector: () => getState(),
  });
}
const renderSettled = (component, props = {}) => {
  component.render(props); component.flushEffects();
  component.render(props); component.flushEffects();
  return component.render(props);
};

test('country names display and match country-only and mixed queries', async () => {
  const records = [
    { id: 1, iataCode: 'HAN', name: 'Noi Bai', city: { name: 'Hà Nội', countryName: 'Việt Nam' } },
    { id: 2, iataCode: 'SGN', name: 'Tan Son Nhat', city: { name: 'Ho Chi Minh', countryName: 'Việt Nam' } },
    { id: 3, iataCode: 'DXB', name: 'Dubai International', city: { name: 'Dubai' } },
  ];
  const component = await loadComponent('pages/traveler/Home/AirportPicker.jsx');
  const props = { label: 'To', airports: records, value: 1, onChange() {} };
  let tree = component.render(props);
  const trigger = findAll(tree, n => n.type === 'button')[0];
  assert.equal(trigger.props.title, 'Hà Nội, Việt Nam');
  assert.match(trigger.props['aria-label'], /HAN Hà Nội, Việt Nam/);
  for (const [query, count] of [['VIET NAM', 2], ['Việt Nam', 2], ['viet han', 1], ['noi bai nam', 1], ['Dubai', 1]]) {
    findAll(tree, n => n.type === 'Input')[0].props.onChange({ target: { value: query } });
    tree = component.render(props);
    assert.equal(findAll(tree, n => n.props.role === 'option').length, count, query);
  }
  tree = component.render({ ...props, value: 3 });
  assert.equal(findAll(tree, n => n.type === 'button')[0].props.title, 'Dubai');
  findAll(tree, n => n.type === 'Input')[0].props.onChange({ target: { value: 'viet nam' } });
  tree = component.render({ ...props, excludedId: 2 });
  assert.equal(findAll(tree, n => n.props.role === 'option').length, 1);
});

test('results form uses submitted criteria without refetching cached airports', async () => {
  let fetches = 0; const calls = [];
  const component = await loadComponent('pages/traveler/Home/FlightSearchBar.jsx', {
    formatDateOnly, listAllAirports: () => { fetches++; return {}; }, useDispatch: () => () => {},
    useSelector: () => ({ airports, loading: false, error: null }),
  });
  const tree = renderSettled(component, {
    initialSearchData: { departureAirportId: 3, arrivalAirportId: 1, departureDate: new Date(2026, 9, 22), passengers: 4, cabinClass: 'BUSINESS' },
    onSearch: data => calls.push(data),
  });
  searchButton(tree).props.onClick();
  assert.equal(fetches, 0);
  assert.deepEqual(calls[0], { departureAirportId: 3, arrivalAirportId: 1, departureDate: '2026-10-22', numberOfTravellers: 4, cabinClass: 'BUSINESS' });
});

test('every special fare can be selected visually without changing search payload', async () => {
  const component = await searchForm(); const calls = [];
  const props = { onSearch: data => calls.push(data) };
  let tree = renderSettled(component, props);
  for (const label of ['Student', 'Armed Forces', 'Senior Citizen', 'Doctor and Nurses', 'Regular']) {
    const button = findAll(tree, n => n.type === 'button' && findAll(n, c => c.type === 'div' && c.props.children.includes(label)).length)[0];
    assert.ok(button && !button.props.disabled, `${label} must be enabled`);
    button.props.onClick(); tree = component.render(props);
    const selected = findAll(tree, n => n.type === 'button' && n.props['aria-pressed'] === true);
    assert.equal(selected.length, 1);
    assert.ok(findAll(selected[0], n => n.props.children.includes(label)).length);
    searchButton(tree).props.onClick();
    assert.equal(calls.at(-1).specialFare, undefined);
  }
});

test('destination prefill uses airport ID and preserves current date and passengers', async () => {
  const component = await searchForm(); const calls = [];
  let props = { onSearch: data => calls.push(data) };
  let tree = renderSettled(component, props);
  findAll(tree, n => n.type === 'Calendar')[0].props.onSelect(new Date(2026, 9, 20));
  findAll(tree, n => n.type === 'button' && n.props.children.includes('+'))[0].props.onClick();
  findAll(tree, n => n.type === 'Select')[0].props.onValueChange('BUSINESS');
  findAll(tree, n => n.type === 'button' && findAll(n, c => c.props.children.includes('Student')).length)[0].props.onClick();
  props = { ...props, destinationPrefill: { iataCode: 'DXB', clickId: 1 } };
  tree = renderSettled(component, props);
  searchButton(tree).props.onClick();
  assert.equal(calls[0].arrivalAirportId, 3);
  assert.equal(calls[0].departureAirportId, 1);
  assert.equal(calls[0].departureDate, '2026-10-20');
  assert.equal(calls[0].numberOfTravellers, 2);
  assert.equal(calls[0].cabinClass, 'BUSINESS');
  assert.ok(findAll(tree, n => n.props['aria-pressed'] === true && findAll(n, c => c.props.children.includes('Student')).length).length);
});

test('repeated destination clicks reapply after manual changes and unavailable destinations retain selections', async () => {
  const component = await searchForm();
  let props = { destinationPrefill: { iataCode: 'DXB', clickId: 1 } };
  let tree = renderSettled(component, props);
  findAll(tree, n => n.type === 'AirportPicker')[1].props.onChange(2);
  tree = renderSettled(component, props);
  assert.equal(findAll(tree, n => n.type === 'AirportPicker')[1].props.value, 2);
  props = { destinationPrefill: { iataCode: 'DXB', clickId: 2 } };
  tree = renderSettled(component, props);
  assert.equal(findAll(tree, n => n.type === 'AirportPicker')[1].props.value, 3);
  tree = renderSettled(component, { destinationPrefill: { iataCode: 'MISSING', clickId: 3 } });
  assert.equal(findAll(tree, n => n.type === 'AirportPicker')[1].props.value, 3);
  assert.match(findAll(tree, n => n.props.role === 'alert')[0].props.children.join(''), /unavailable/);
});

test('prefill waits for airport loading and clears matching origin, including initial defaults', async () => {
  let state = { airports: [], loading: true, error: null };
  const component = await searchForm(() => state);
  const props = { destinationPrefill: { iataCode: 'HAN', clickId: 1 } };
  let tree = renderSettled(component, props);
  assert.equal(findAll(tree, n => n.props.role === 'alert').length, 0);
  state = { airports, loading: false, error: null };
  tree = renderSettled(component, props);
  const pickers = findAll(tree, n => n.type === 'AirportPicker');
  assert.equal(pickers[0].props.value, null);
  assert.equal(pickers[1].props.value, 1);
  assert.match(findAll(tree, n => n.props.role === 'alert')[0].props.children.join(''), /departure/);
  state = { ...state, airports: [...airports] };
  tree = renderSettled(component, props);
  assert.equal(findAll(tree, n => n.type === 'AirportPicker')[0].props.value, null);
});

test('manual selections and swaps survive delayed default initialization', async () => {
  let state = { airports: [airports[2]], loading: true, error: null };
  const component = await searchForm(() => state);
  let tree = renderSettled(component);
  findAll(tree, n => n.type === 'AirportPicker')[0].props.onChange(3);
  state = { airports, loading: false, error: null };
  tree = renderSettled(component);
  assert.equal(findAll(tree, n => n.type === 'AirportPicker')[0].props.value, 3);
  assert.equal(findAll(tree, n => n.type === 'AirportPicker')[1].props.value, null);
  findAll(tree, n => n.type === 'AirportPicker')[1].props.onChange(1);
  tree = component.render();
  findAll(tree, n => n.props['aria-label'] === 'Swap departure and arrival airports')[0].props.onClick();
  tree = component.render();
  assert.deepEqual(findAll(tree, n => n.type === 'AirportPicker').map(n => n.props.value), [1, 3]);
  assert.deepEqual(findAll(tree, n => n.type === 'AirportPicker').map(n => n.props.excludedId), [3, 1]);
});

test('successfully loaded empty airport list reports unavailable destination', async () => {
  const component = await searchForm(() => ({ airports: [], loading: false, error: null }));
  const props = { destinationPrefill: { iataCode: 'DXB', clickId: 1 } };
  renderSettled(component, props); await settle();
  const tree = renderSettled(component, props);
  assert.match(findAll(tree, n => n.props.role === 'alert')[0].props.children.join(''), /unavailable/);
});

test('airport picker filters city, code and airport name without accents and retains numeric selection', async () => {
  const changes = [];
  const component = await loadComponent('pages/traveler/Home/AirportPicker.jsx');
  const props = { label: 'From', airports, value: 3, excludedId: 2, onChange: id => changes.push(id) };
  let tree = component.render(props);
  findAll(tree, n => n.type === 'Popover')[0].props.onOpenChange(true);
  tree = component.render(props);
  for (const query of ['ha noi', 'han', 'NOI BAI']) {
    findAll(tree, n => n.type === 'Input')[0].props.onChange({ target: { value: query } });
    tree = component.render(props);
    const options = findAll(tree, n => n.props.role === 'option');
    assert.equal(options.length, 1);
    assert.equal(options[0].props.id.endsWith('-1'), true);
  }
  assert.deepEqual(changes, []);
  findAll(tree, n => n.props.role === 'option')[0].props.onClick();
  assert.deepEqual(changes, [1]);
  assert.equal(findAll(component.render(props), n => n.type === 'Popover')[0].props.open, false);
});

test('picker caps matches, supports arrows, Enter and Escape, and displays loading and empty states', async () => {
  const changes = [];
  const component = await loadComponent('pages/traveler/Home/AirportPicker.jsx');
  const props = { label: 'To', airports: Array.from({ length: 2000 }, (_, index) => ({ id: index + 1, iataCode: `A${index}`, name: 'Airport', city: { name: 'City' } })), value: null, onChange: id => changes.push(id) };
  let tree = component.render(props);
  findAll(tree, n => n.type === 'Popover')[0].props.onOpenChange(true);
  tree = component.render(props);
  assert.equal(findAll(tree, n => n.props.role === 'option').length, 50);
  assert.match(findAll(tree, n => n.props.role === 'status')[0].props.children.join(''), /refine/);
  const key = key => findAll(tree, n => n.type === 'Input')[0].props.onKeyDown({ key, preventDefault() {} });
  key('ArrowDown'); tree = component.render(props); key('Enter');
  assert.deepEqual(changes, [2]);
  tree = component.render(props); key('Escape'); tree = component.render(props);
  assert.equal(findAll(tree, n => n.type === 'Popover')[0].props.open, false);
  findAll(tree, n => n.type === 'Input')[0].props.onChange({ target: { value: 'missing' } });
  tree = component.render(props);
  assert.match(findAll(tree, n => n.props.role === 'status')[0].props.children.join(''), /No airports/);
  tree = component.render({ ...props, loading: true });
  assert.equal(findAll(tree, n => n.props.role === 'option').length, 0);
  assert.match(findAll(tree, n => n.props.role === 'status')[0].props.children.join(''), /Loading/);
  tree = component.render({ ...props, error: 'Offline' });
  assert.match(findAll(tree, n => n.props.role === 'status')[0].props.children.join(''), /Unable/);
});

test('popular destination cards prefill and scroll without navigation, then submit date-only search', async () => {
  const routes = []; let scrolls = 0;
  const component = await loadComponent('pages/traveler/Home/HomePage.jsx', { useNavigate: () => route => routes.push(route), formatDateOnly });
  let tree = component.render();
  findAll(tree, n => n.props.ref)[0].props.ref.current = { scrollIntoView() { scrolls++; } };
  findAll(tree, n => n.props['aria-label'] === 'Search flights to Dubai')[0].props.onClick();
  tree = component.render();
  const form = findAll(tree, n => n.type === 'FlightSearchBar')[0];
  assert.equal(form.props.destinationPrefill.iataCode, 'DXB');
  assert.equal(scrolls, 1); assert.equal(routes.length, 0);
  form.props.onSearch({ departureAirportId: 1, arrivalAirportId: 3, departureDate: new Date(2026, 9, 20), numberOfTravellers: 2 });
  const params = new URLSearchParams(routes[0].split('?')[1]);
  assert.equal(params.get('departureDate'), '2026-10-20');
  assert.equal(params.get('arrivalAirportId'), '3');
});
