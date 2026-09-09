import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { createServer } from 'vite';
import { findAll, loadComponent } from './componentHarness.mjs';

const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom', optimizeDeps: { noDiscovery: true, include: [] } });
after(() => server.close());
const { default: reducer, clearAircraftState } = await server.ssrLoadModule('/src/Redux/aircraft/aircraftSlice.js');
const { listAllAircrafts, createAircraft, updateAircraft, deleteAircraft } = await server.ssrLoadModule('/src/Redux/aircraft/aircraftThunks.js');

async function summary(initialState) {
  let state = initialState;
  const component = await loadComponent('pages/airline/Dashboard/AircraftManagement/AircraftListPage.jsx', {
    useSelector: selector => selector({ aircraft: state }),
    useDispatch: () => () => {},
    useNavigate: () => () => {},
  });
  return nextState => {
    state = nextState ?? state;
    return findAll(component.render(), node => node.type === 'p' && node.props.className?.includes('text-2xl'))
      .map(node => node.props.children.join(''));
  };
}

function loadFleet(fleet, params = {}, state) {
  state = reducer(state, listAllAircrafts.pending('fleet', params));
  return reducer(state, listAllAircrafts.fulfilled(fleet, 'fleet', params));
}

const aircraft = { id: 1, code: 'SMOKE-12', status: 'ACTIVE', seatingCapacity: 12 };

test('smoke fixture shows one active aircraft and twelve seats', async () => {
  const render = await summary(loadFleet([aircraft]));
  assert.deepEqual(render(), ['1', '1', '0', '12']);
});

test('fleet summaries remain independent of search, status filters and pagination', async () => {
  const fleet = [aircraft,
    { id: 2, code: 'M', status: 'MAINTENANCE', seatingCapacity: 1000 },
    { id: 3, code: 'I', status: 'INACTIVE', totalSeats: 50 },
    { id: 4, code: 'R', status: 'RETIRED', seatingCapacity: 0, totalSeats: 100 },
    { id: 5, code: 'A', status: 'ACTIVE', seatingCapacity: null, totalSeats: null }];
  const render = await summary(loadFleet(fleet));
  const expected = ['5', '2', '1', '1,062'];
  assert.deepEqual(render(), expected);
  for (const params of [{ size: 1, page: 2 }, { search: 'SMOKE' }, { status: 'MAINTENANCE' }]) {
    const state = loadFleet(fleet, params);
    assert.equal(state.paginatedAircrafts.content.length, 1);
    assert.deepEqual(render(state), expected);
  }
});

test('unloaded and failed requests show dashes; a successfully loaded empty fleet shows zeros', async () => {
  let state = reducer(undefined, { type: 'init' });
  const render = await summary(state);
  assert.deepEqual(render(), ['—', '—', '—', '—']);
  state = loadFleet([aircraft], {}, state);
  assert.deepEqual(render(state), ['1', '1', '0', '12']);
  state = reducer(state, listAllAircrafts.pending('retry', {}));
  assert.deepEqual(render(state), ['—', '—', '—', '—']);
  state = reducer(state, listAllAircrafts.rejected(new Error('offline'), 'retry', {}, 'Offline'));
  assert.deepEqual(render(state), ['—', '—', '—', '—']);
  assert.deepEqual(render(loadFleet([], {}, state)), ['0', '0', '0', '0']);
});

test('successful create, edit and delete operations update the fleet summaries', async () => {
  let state = loadFleet([aircraft]);
  const render = await summary(state);
  state = reducer(state, createAircraft.fulfilled({ id: 2, status: 'MAINTENANCE', seatingCapacity: 20 }, 'create', {}));
  assert.deepEqual(render(state), ['2', '1', '1', '32']);
  state = reducer(state, updateAircraft.fulfilled({ ...aircraft, status: 'MAINTENANCE', seatingCapacity: 15 }, 'edit', {}));
  assert.deepEqual(render(state), ['2', '0', '2', '35']);
  state = reducer(state, deleteAircraft.fulfilled(2, 'delete', 2));
  assert.deepEqual(render(state), ['1', '0', '1', '15']);
});

test('clearing the fleet ignores late list responses and clears its metrics', async () => {
  let state = loadFleet([aircraft]);
  const render = await summary(state);
  state = reducer(state, listAllAircrafts.pending('late', {}));
  state = reducer(state, clearAircraftState());
  state = reducer(state, listAllAircrafts.fulfilled([aircraft], 'late', {}));
  assert.deepEqual(render(state), ['—', '—', '—', '—']);
});

test('a paginated response is not mistaken for a complete fleet', async () => {
  const state = loadFleet({ content: [aircraft], totalElements: 100, number: 0 });
  const render = await summary(state);
  assert.deepEqual(render(), ['—', '—', '—', '—']);
});
