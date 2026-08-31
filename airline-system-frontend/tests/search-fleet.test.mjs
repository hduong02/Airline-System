import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { createServer } from 'vite';
import { formatDateOnly, parseDateOnly } from '../src/utils/dateOnly.js';
import { loadComponent, findAll } from './componentHarness.mjs';
const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom', optimizeDeps: { noDiscovery: true, include: [] } });
after(() => server.close());
const { default: reducer } = await server.ssrLoadModule('/src/Redux/flightSearch/flightSearchSlice.js');
const { searchFlightsAvailability: search } = await server.ssrLoadModule('/src/Redux/flightSearch/flightSearchThunk.js');
test('superseded search responses cannot replace current results', () => {
  let state = reducer(undefined, search.pending('old', {}));
  state = reducer(state, search.pending('new', {}));
  state = reducer(state, search.fulfilled({ content: [{id: 2}], number: 0, last: true }, 'new', {}));
  state = reducer(state, search.fulfilled({ content: [{id: 1}], number: 0 }, 'old', {}));
  assert.equal(state.searchResults.content[0].id, 2);
});
test('initial search failure has a displayable empty page', () => {
  let state = reducer(undefined, search.pending('first', {}));
  state = reducer(state, search.rejected(new Error('failure'), 'first', {}, 'Search failed'));
  assert.deepEqual(state.searchResults.content, []);
  assert.equal(state.error, 'Search failed');
});
test('search callback receives local calendar date and rejects identical airports', async () => {
  const calls = [];
  const component = await loadComponent('pages/traveler/Home/FlightSearchBar.jsx', {
    formatDateOnly, listAllAirports: () => ({}), useDispatch: () => () => {}, useSelector: () => ({airports: [{id: 1, iataCode: 'HAN'}, {id: 2, iataCode: 'SGN'}]})
  });
  let tree = component.render({onSearch: data => calls.push(data)}); component.flushEffects();
  tree = component.render({onSearch: data => calls.push(data)});
  findAll(tree, n => n.type === 'Calendar')[0].props.onSelect(new Date(2026, 9, 15));
  tree = component.render({onSearch: data => calls.push(data)});
  findAll(tree, n => n.type === 'Button' && findAll(n, child => child.type === 'span' && child.props.children.includes('Search Flights')).length > 0)[0].props.onClick();
  assert.equal(calls[0].departureDate, '2026-10-15');
  findAll(tree, n => n.type === 'Select')[0].props.onValueChange('2');
  tree = component.render({onSearch: data => calls.push(data)});
  findAll(tree, n => n.type === 'Button' && findAll(n, child => child.type === 'span' && child.props.children.includes('Search Flights')).length > 0)[0].props.onClick();
  assert.equal(calls.length, 1);
  assert.equal(findAll(component.render(), n => n.props.role === 'alert').length, 1);
  const buttons = findAll(tree, n => n.type === 'button');
  assert.ok(buttons.filter(n => n.props.title?.includes('not available')).every(n => n.props.disabled));

});

test('load more appends pages, ignores old errors, and permits retry after failure', () => {
  let state = reducer(undefined, search.pending('first', {page: 0}));
  state = reducer(state, search.fulfilled({content: [{id: 1}], number: 0, last: false}, 'first', {page: 0}));
  state = reducer(state, search.pending('more', {page: 1}));
  assert.equal(state.loadingMore, true); assert.equal(state.loading, false);
  state = reducer(state, search.rejected(new Error('old failure'), 'first', {}, 'Old failure'));
  assert.equal(state.error, null); assert.equal(state.loadingMore, true);
  state = reducer(state, search.rejected(new Error('failure'), 'more', {page: 1}, 'Offline'));
  assert.equal(state.searchResults.number, 0); assert.equal(state.error, 'Offline');
  state = reducer(state, search.pending('retry', {page: 1}));
  state = reducer(state, search.fulfilled({content: [{id: 1}, {id: 2}], number: 1, last: true}, 'retry', {page: 1}));
  assert.deepEqual(state.searchResults.content.map(f => f.id), [1, 2]);
  assert.equal(state.searchResults.last, true);
});

test('aircraft search resets a later page atomically, while unchanged search retains it', async () => {
  const {default: aircraftReducer, setSearchKeyword, setCurrentPage} = await server.ssrLoadModule('/src/Redux/aircraft/aircraftSlice.js');
  let state = aircraftReducer(undefined, setCurrentPage(3));
  state = aircraftReducer(state, setSearchKeyword(''));
  assert.equal(state.currentPage, 3);
  state = aircraftReducer(state, setSearchKeyword('Boeing'));
  assert.equal(state.currentPage, 0); assert.equal(state.searchKeyword, 'Boeing');
});

test('schedule validates today and unchanged historical starts, rejecting changed past starts and invalid dates', async () => {
  const {createFlightScheduleSchema} = await server.ssrLoadModule('/src/utils/flightScheduleValidation.js');
  const today = formatDateOnly(new Date());
  const values = { flightId: '1', departureTime: '08:30', arrivalTime: '10:00', recurrenceType: 'DAILY', operatingDays: [], startDate: today, endDate: today };
  assert.equal(await createFlightScheduleSchema().isValid(values), true);
  assert.equal(await createFlightScheduleSchema('2020-01-01').isValid({...values, startDate: '2020-01-01'}), true);
  assert.equal(await createFlightScheduleSchema('2020-01-01').isValid({...values, startDate: '2020-01-02'}), false);
  assert.equal(await createFlightScheduleSchema().isValid({...values, startDate: '2026-02-30'}), false);
  assert.equal(await createFlightScheduleSchema().isValid({...values, arrivalTime: '25:00'}), false);
  assert.equal(await createFlightScheduleSchema('2020-01-02').isValid({...values, startDate: '2020-01-02', endDate: '2020-01-01'}), false);
});

test('airline loading fetches every backend page and retains pagination metadata', async () => {
  const {configureStore} = await import('@reduxjs/toolkit');
  const {default: flightReducer} = await server.ssrLoadModule('/src/Redux/flight/flightSlice.js');
  const {getFlightsByAirline} = await server.ssrLoadModule('/src/Redux/flight/flightThunk.js');
  const {default: api} = await server.ssrLoadModule('/src/utils/api.js');
  globalThis.localStorage = {getItem: () => null};
  const pages = [];
  api.get = async (url, options) => { pages.push(options.params.page); return {data: {content: [{id: options.params.page + 1}], number: options.params.page, totalPages: 3, totalElements: 3}}; };
  const store = configureStore({reducer: {flight: flightReducer}});
  await store.dispatch(getFlightsByAirline()).unwrap();
  assert.deepEqual(pages, [0, 1, 2]);
  assert.deepEqual(store.getState().flight.flights.map(f => f.id), [1, 2, 3]);
  assert.equal(store.getState().flight.flightPage.totalPages, 3);
});

test('editing an aircraft updates the existing ID and preserves date-only input', async () => {
  const calls = []; const routes = [];
  const fixture = {id: 9, code: 'A1', model: 'A320', manufacturer: 'Airbus', seatingCapacity: 100, economySeats: 100,
    yearOfManufacture: 2020, registrationDate: '2026-10-15', status: 'ACTIVE'};
  const form = await loadComponent('pages/airline/Dashboard/AircraftManagement/AircraftForm.jsx', {
    formatDateOnly, parseDateOnly, useParams: () => ({}), useNavigate: () => route => routes.push(route),
    useDispatch: () => action => ({unwrap: async () => {calls.push(action); return fixture;}}),
    updateAircraft: data => ({kind: 'update', ...data}), createAircraft: data => ({kind: 'create', data}),
  });
  const props = {isEditMode: true, aircraftData: fixture};
  form.render(props); form.flushEffects();
  const tree = form.render(props);
  assert.equal(findAll(tree, n => n.props.id === 'registrationDate' && n.type === 'Input')[0].props.value, '2026-10-15');
  await findAll(tree, n => n.type === 'form')[0].props.onSubmit({preventDefault() {}});
  assert.equal(calls[0].kind, 'update'); assert.equal(calls[0].aircraftId, 9);
  assert.equal(calls[0].aircraftData.registrationDate, '2026-10-15');
  assert.deepEqual(routes, ['/airline/aircraft']);
});

test('URL changes refetch, abort previous searches, and modified search uses canonical parameters', async (t) => {
  let params = new URLSearchParams('departureAirportId=1&arrivalAirportId=2&departureDate=2026-10-15&numberOfTravellers=3');
  const timers = []; const requests = []; const routes = []; let aborted = 0;
  t.mock.method(globalThis, 'setTimeout', fn => {timers.push(fn); return fn;});
  t.mock.method(globalThis, 'clearTimeout', () => {});
  const state = {airline: {dropdownAirlines: []}, airport: {airports: [{id: 1}, {id: 2}]},
    flightSearch: {searchResults: {content: [], number: 0, last: true}, loading: false, error: null, loadingMore: false}};
  const dispatch = action => {if (action.kind === 'search') requests.push(action.data); return {abort() {aborted++;}};};
  const component = await loadComponent('pages/traveler/FlightList/SearchResults.jsx', {
    useSearchParams: () => [params], useNavigate: () => route => routes.push(route), useDispatch: () => dispatch,
    useSelector: selector => selector(state), parseDateOnly, formatDateOnly,
    clearSearchResults: () => ({kind: 'clear'}), getAirlinesForDropdown: () => ({}),
    searchFlightsAvailability: data => ({kind: 'search', data}),
  });
  component.render(); component.flushEffects(); timers.shift()();
  assert.equal(requests.length, 1); assert.equal(requests[0].passengers, 3);
  params = new URLSearchParams('departureAirportId=2&arrivalAirportId=1&departureDate=2026-10-16&numberOfTravellers=2');
  let tree = component.render(); component.flushEffects(); timers.shift()();
  assert.equal(aborted, 1); assert.equal(requests[1].departureAirportId, 2); assert.equal(requests[1].departureDate, '2026-10-16');
  findAll(tree, n => n.type === 'SearchSummaryBar')[0].props.onModifySearch({departureAirportId: 1, arrivalAirportId: 2, departureDate: '2026-10-17', numberOfTravellers: 4});
  const destination = new URLSearchParams(routes[0].split('?')[1]);
  assert.equal(destination.get('numberOfTravellers'), '4'); assert.equal(destination.get('departureDate'), '2026-10-17');
  assert.equal(requests.length, 2);
  state.flightSearch.searchResults = {content: [{id: 5}], number: 0, last: false};
  tree = component.render();
  const button = findAll(tree, n => n.type === 'Button' && n.props.children.includes('Load more flights'))[0];
  button.props.onClick(); assert.equal(requests[2].page, 1);
  state.flightSearch.searchResults.last = true;
  assert.equal(findAll(component.render(), n => n.type === 'Button' && n.props.children.includes('Load more flights')).length, 0);
  component.unmount();
});

test('schedule edit normalizes backend seconds before validation', async () => {
  const {createFlightScheduleSchema} = await server.ssrLoadModule('/src/utils/flightScheduleValidation.js');
  const thunk = () => ({kind: 'schedule'}); thunk.fulfilled = {match: result => result.type === 'fulfilled'};
  const dispatch = action => Promise.resolve(action.kind === 'schedule' ? {type: 'fulfilled', payload: {
    flightId: 1, departureTime: '08:30:00', arrivalTime: '10:00:00', startDate: '2020-01-01', endDate: formatDateOnly(new Date()), recurrenceType: 'DAILY'
  }} : {});
  const form = await loadComponent('pages/airline/Dashboard/FlightSchedules/FlightScheduleForm.jsx', {
    useParams: () => ({id: '7'}), useNavigate: () => () => {}, useDispatch: () => dispatch,
    useSelector: selector => selector({flight: {flights: []}, flightSchedule: {loading: false}}),
    getFlightsByAirline: () => ({}), getFlightScheduleById: thunk, listAllAirports: () => ({}),
    createFlightScheduleSchema, formatDateOnly, parseDateOnly,
  });
  form.render(); form.flushEffects(); await Promise.resolve();
  const formik = findAll(form.render(), n => n.type === 'Formik')[0];
  assert.equal(formik.props.initialValues.departureTime, '08:30');
  assert.equal(formik.props.initialValues.arrivalTime, '10:00');
  assert.equal(await formik.props.validationSchema.isValid(formik.props.initialValues), true);
});

test('aircraft list arrays are filtered, sorted and paginated with real counts', async () => {
  const {default: aircraftReducer} = await server.ssrLoadModule('/src/Redux/aircraft/aircraftSlice.js');
  const {listAllAircrafts} = await server.ssrLoadModule('/src/Redux/aircraft/aircraftThunks.js');
  const args = {page: 0, size: 1, search: 'Boeing', status: 'ACTIVE', sortBy: 'code', sortDirection: 'desc'};
  let state = aircraftReducer(undefined, listAllAircrafts.pending('list', args));
  state = aircraftReducer(state, listAllAircrafts.fulfilled([
    {id: 1, code: 'A', model: 'Boeing', status: 'ACTIVE'}, {id: 2, code: 'B', model: 'Boeing', status: 'ACTIVE'},
    {id: 3, code: 'C', model: 'Airbus', status: 'ACTIVE'}, {id: 4, code: 'D', model: 'Boeing', status: 'GROUNDED'},
  ], 'list', args));
  assert.equal(state.aircrafts.length, 4); assert.equal(state.paginatedAircrafts.totalElements, 2);
  assert.equal(state.paginatedAircrafts.content[0].id, 2); assert.equal(state.paginatedAircrafts.last, false);
});

test('aircraft delete asks for confirmation and backs up when the last row on a page is deleted', async () => {
  const actions = [];
  const state = {aircraft: {currentPage: 2, pageSize: 10, searchKeyword: '', statusFilter: 'all', paginatedAircrafts: {content: [{id: 9}]}}};
  const component = await loadComponent('pages/airline/Dashboard/AircraftManagement/AircraftListPage.jsx', {
    useNavigate: () => () => {}, useSelector: selector => selector(state),
    useDispatch: () => action => {actions.push(action); return {unwrap: async () => {}};},
    deleteAircraft: id => ({kind: 'delete', id}), setCurrentPage: page => ({kind: 'page', page}), listAllAircrafts: params => ({kind: 'list', params}),
  });
  findAll(component.render(), n => n.type === 'AircraftTable')[0].props.onDelete({id: 9, code: 'A1'});
  assert.equal(actions.length, 0);
  const tree = component.render(); assert.equal(findAll(tree, n => n.type === 'Dialog')[0].props.open, true);
  await findAll(tree, n => n.type === 'Button' && n.props.variant === 'destructive')[0].props.onClick();
  assert.deepEqual(actions, [{kind: 'delete', id: 9}, {kind: 'page', page: 1}]);
  assert.equal(findAll(component.render(), n => n.type === 'Dialog')[0].props.open, false);
});
