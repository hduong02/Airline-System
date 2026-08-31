import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { createServer } from 'vite';
import { loadComponent, findAll, deferred, settle } from './componentHarness.mjs';
import { readOnboardingProgress, sanitizeOnboardingData } from '../src/utils/onboardingProgress.js';
import { getLoginDestination, homeForRole } from '../src/utils/authNavigation.js';

const memoryStorage = () => {
  const values = new Map();
  return { getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, String(value)),
    removeItem: (key) => values.delete(key), clear: () => values.clear() };
};
globalThis.localStorage = memoryStorage();
globalThis.sessionStorage = memoryStorage();
const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom', optimizeDeps: { noDiscovery: true, include: [] } });
after(() => server.close());
const storeModule = await server.ssrLoadModule('/src/Redux/globleState.js');
const { getUserProfile, logout } = await server.ssrLoadModule('/src/Redux/user/userThunks.js');
const { login } = await server.ssrLoadModule('/src/Redux/auth/authThunk.js');
const { createBooking } = await server.ssrLoadModule('/src/Redux/booking/bookingThunk.js');
const { initializeAnonymous, sessionExpired } = await server.ssrLoadModule('/src/Redux/auth/authSlice.js');
const { clearSessionStorage } = await server.ssrLoadModule('/src/Redux/sessionState.js');
const { default: api } = await server.ssrLoadModule('/src/utils/api.js');
const newStore = () => storeModule.createAppStore ? storeModule.createAppStore() : storeModule.default;

test('logout clears private slices immediately and blocks late booking/profile responses', async () => {
  const store = newStore();
  const response = deferred();
  api.get = () => response.promise;
  localStorage.setItem('jwt', 'fixture-token');
  sessionStorage.setItem('paymentDetails', 'fixture-payment');
  localStorage.setItem('airline_onboarding_progress', 'fixture-progress');
  const profile = store.dispatch(getUserProfile('fixture-token'));
  store.dispatch({ type: 'booking/getByUser/pending', meta: { requestId: 'old-bookings', requestStatus: 'pending' } });
  store.dispatch({ type: 'booking/getByUser/fulfilled', payload: [{ id: 999 }], meta: { requestId: 'old-bookings', requestStatus: 'fulfilled' } });
  store.dispatch({ type: 'booking/getById/pending', meta: { requestId: 'late-booking', requestStatus: 'pending' } });
  const loggingOut = store.dispatch(logout());
  assert.equal(store.getState().auth.isAuthenticated, false);
  assert.deepEqual(store.getState().booking.bookings, []);
  await loggingOut;
  response.resolve({ data: { id: 1, role: 'ROLE_USER' } }); await profile;
  store.dispatch({ type: 'booking/getById/fulfilled', payload: { id: 999 }, meta: { requestId: 'late-booking', requestStatus: 'fulfilled' } });
  assert.equal(store.getState().auth.isAuthenticated, false);
  assert.equal(store.getState().user.userProfile, null);
  assert.equal(store.getState().booking.booking, null);
  assert.equal(sessionStorage.getItem('paymentDetails'), null);
  assert.equal(localStorage.getItem('airline_onboarding_progress'), null);
});

test('logout during login cannot restore the JWT or auth state and credentials are never logged', async (t) => {
  const store = newStore();
  const response = deferred();
  api.post = () => response.promise;
  const captured = [];
  for (const method of ['log', 'error']) t.mock.method(console, method, (...args) => captured.push(args));
  const pending = store.dispatch(login({ email: 'fixture@example.com', password: 'fixture-password' }));
  await store.dispatch(logout());
  response.resolve({ data: { jwt: 'fixture-secret-token', user: { id: 1, role: 'ROLE_USER' } } });
  await pending;
  assert.equal(localStorage.getItem('jwt'), null);
  assert.equal(store.getState().auth.isAuthenticated, false);
  assert.ok(!JSON.stringify(captured).includes('fixture-password'));
  assert.ok(!JSON.stringify(captured).includes('fixture-secret-token'));
});

test('onboarding saves only non-secret fields', async () => {
  localStorage.clear();
  const wizard = await loadComponent('pages/Onboarding/AirlineOnboardingWizard.jsx', {
    useNavigate: () => () => {}, readOnboardingProgress, sanitizeOnboardingData,
  });
  wizard.render(); wizard.flushEffects();
  const owner = findAll(wizard.render(), (node) => node.type === 'OwnerDetailsStep')[0];
  owner.props.onDataChange({ fullName: 'Fixture Owner', email: 'fixture@example.com', userId: 1,
    password: 'fixture-password', confirmPassword: 'fixture-password', jwt: 'fixture-token' });
  owner.props.onNext();
  wizard.render(); wizard.flushEffects();
  const progress = JSON.parse(localStorage.getItem('airline_onboarding_progress'));
  assert.equal(progress.formData.owner.fullName, 'Fixture Owner');
  for (const key of ['password', 'confirmPassword', 'jwt']) assert.equal(progress.formData.owner[key], undefined);
  assert.equal(progress.currentStep, 2);
});

test('private guards wait for initialization, redirect anonymous users, and enforce roles', async () => {
  let auth = { initialized: false, isAuthenticated: false, user: null };
  const guard = await loadComponent('components/SessionRoutes.jsx', {
    useSelector: (selector) => selector({ auth }), useLocation: () => ({ pathname: '/airline/dashboard', search: '?tab=1' }),
  }, 'RequireSession');
  assert.equal(guard.render().props.role, 'status');
  auth.initialized = true;
  assert.equal(guard.render().type, 'Navigate');
  assert.equal(guard.render().props.to, '/login');
  assert.equal(guard.render().props.state.from, '/airline/dashboard?tab=1');
  auth = { initialized: true, isAuthenticated: true, user: { role: 'ROLE_USER' } };
  assert.equal(guard.render({ roles: ['ROLE_AIRLINE_OWNER'] }).props.to, '/');
  assert.equal(guard.render().type, 'Outlet');
  auth.user.role = 'ROLE_AIRLINE_OWNER';
  assert.equal(guard.render({ roles: ['ROLE_AIRLINE_OWNER'] }).type, 'Outlet');
  assert.equal(guard.render({ roles: ['ROLE_SYSTEM_ADMIN'] }).props.to, '/');
  auth.user.role = 'ROLE_SYSTEM_ADMIN';
  assert.equal(guard.render({ roles: ['ROLE_SYSTEM_ADMIN'] }).type, 'Outlet');
});

test('auth pages do not render for authenticated users or during initialization', async () => {
  let auth = { initialized: false };
  const guard = await loadComponent('components/SessionRoutes.jsx', {
    useSelector: (selector) => selector({ auth }), useLocation: () => ({ state: null }), homeForRole, getLoginDestination,
  }, 'AuthProtected');
  assert.equal(guard.render({ children: 'private-form' }).props.role, 'status');
  auth = { initialized: true, isAuthenticated: false };
  assert.equal(guard.render({ children: 'login-form' }), 'login-form');
  for (const [role, home] of [['ROLE_USER', '/traveler'], ['ROLE_AIRLINE_OWNER', '/airline'], ['ROLE_SYSTEM_ADMIN', '/super-admin']]) {
    auth = { initialized: true, isAuthenticated: true, user: { role } };
    assert.equal(guard.render({ children: 'login-form' }).props.to, home);
  }
});

test('expired profile initialization clears the session and private state', async () => {
  const store = newStore();
  localStorage.setItem('jwt', 'expired-token');
  api.get = async () => { throw { response: { status: 401, data: { message: 'Unauthorized' } } }; };
  store.dispatch({ type: 'booking/getByUser/fulfilled', payload: [{ id: 999 }] });
  await store.dispatch(getUserProfile('expired-token'));
  assert.equal(store.getState().auth.initialized, true);
  assert.equal(store.getState().auth.isAuthenticated, false);
  assert.deepEqual(store.getState().booking.bookings, []);
  assert.equal(localStorage.getItem('jwt'), null);
});

test('switching accounts ignores the previous session bookings and older profile requests', async () => {
  const store = newStore();
  store.dispatch({ type: 'booking/getByUser/pending', meta: { requestId: 'old', requestStatus: 'pending' } });
  api.post = async () => ({ data: { jwt: 'new-token', user: { id: 2, role: 'ROLE_USER' } } });
  await store.dispatch(login({ email: 'new@example.com', password: 'fixture-password' }));
  store.dispatch({ type: 'booking/getByUser/fulfilled', payload: [{ id: 999 }], meta: { requestId: 'old', requestStatus: 'fulfilled' } });
  assert.deepEqual(store.getState().booking.bookings, []);
  const first = deferred(), second = deferred();
  let calls = 0;
  api.get = () => ++calls === 1 ? first.promise : second.promise;
  const oldProfile = store.dispatch(getUserProfile('new-token'));
  const newProfile = store.dispatch(getUserProfile('new-token'));
  second.resolve({ data: { id: 2, fullName: 'New profile', role: 'ROLE_USER' } }); await newProfile;
  first.resolve({ data: { id: 1, fullName: 'Old profile', role: 'ROLE_USER' } }); await oldProfile;
  assert.equal(store.getState().auth.user.id, 2);
  assert.equal(store.getState().user.userProfile.id, 2);
});

test('legacy onboarding progress is scrubbed on restore and malformed progress recovers', () => {
  localStorage.setItem('jwt', 'fixture-token');
  localStorage.setItem('airline_onboarding_progress', JSON.stringify({ currentStep: 3, formData: {
    owner: { fullName: 'Fixture Owner', password: 'fixture-password', confirmPassword: 'fixture-password', jwt: 'fixture-token' },
  } }));
  const progress = readOnboardingProgress();
  assert.equal(progress.currentStep, 3);
  assert.deepEqual(progress.formData.owner, { fullName: 'Fixture Owner' });
  const saved = localStorage.getItem('airline_onboarding_progress');
  assert.ok(!saved.includes('fixture-password'));
  assert.ok(!saved.includes('fixture-token'));
  localStorage.setItem('airline_onboarding_progress', '{invalid');
  assert.equal(readOnboardingProgress().currentStep, 1);
  assert.equal(localStorage.getItem('airline_onboarding_progress'), null);
});

test('late booking creation cannot save checkout details or redirect after logout', async () => {
  const store = newStore();
  const response = deferred();
  api.post = () => response.promise;
  const previousWindow = globalThis.window;
  globalThis.window = { location: { href: 'original-location' } };
  try {
    const request = store.dispatch(createBooking({ flightInstanceId: 7 }));
    await store.dispatch(logout());
    response.resolve({ data: { success: true, checkoutUrl: 'https://checkout.example.test', paymentId: 1 } });
    const result = await request;
    assert.equal(result.meta.requestStatus, 'rejected');
    assert.equal(sessionStorage.getItem('paymentDetails'), null);
    assert.equal(window.location.href, 'original-location');
  } finally { globalThis.window = previousWindow; }
});

test('session initializer handles anonymous entry and cross-tab logout/account switching', async () => {
  const store = newStore();
  localStorage.clear();
  const listeners = new Map();
  const previousWindow = globalThis.window;
  globalThis.window = {
    addEventListener: (name, callback) => listeners.set(name, callback),
    removeEventListener: (name) => listeners.delete(name),
  };
  api.get = async () => ({ data: { id: 2, role: 'ROLE_AIRLINE_OWNER' } });
  const initializer = await loadComponent('components/SessionRoutes.jsx', {
    useDispatch: () => store.dispatch, initializeAnonymous, sessionExpired, getUserProfile, clearSessionStorage,
  }, 'SessionInitializer');
  try {
    initializer.render(); initializer.flushEffects();
    assert.equal(store.getState().auth.initialized, true);
    assert.equal(store.getState().auth.isAuthenticated, false);
    localStorage.setItem('jwt', 'other-tab-token');
    listeners.get('storage')({ key: 'jwt' }); await settle();
    assert.equal(localStorage.getItem('jwt'), 'other-tab-token');
    assert.equal(store.getState().auth.user.id, 2);
    localStorage.removeItem('jwt');
    listeners.get('storage')({ key: 'jwt' }); await settle();
    assert.equal(store.getState().auth.isAuthenticated, false);
  } finally { initializer.unmount(); globalThis.window = previousWindow; }
});

test('login preserves private callback destinations and rejects external or forbidden redirects', () => {
  assert.equal(getLoginDestination('ROLE_USER', '/booking-success/7?session_id=cs_real'), '/booking-success/7?session_id=cs_real');
  assert.equal(getLoginDestination('ROLE_USER', '/super-admin/users'), '/traveler');
  assert.equal(getLoginDestination('ROLE_SYSTEM_ADMIN', '/super-admin/users'), '/super-admin/users');
  for (const from of ['https://example.test', '//example.test', '/\\example.test', '/login', '/unknown']) {
    assert.equal(getLoginDestination('ROLE_USER', from), '/traveler');
  }
});

test('App wraps every private page and both dashboards in the intended guards', async () => {
  const app = await loadComponent('App.jsx');
  const routes = findAll(app.render(), (node) => node.type === 'Route');
  const privateGroup = routes.find((route) => route.props.element?.type === 'RequireSession' && !route.props.element.props.roles);
  assert.ok(privateGroup);
  const paths = findAll(privateGroup.props.children, (node) => node.type === 'Route').map((route) => route.props.path);
  for (const path of ['/profile', '/bookings', '/booking-review', '/payment', '/booking-success/:bookingId',
    '/payment-cancelled/:paymentId', '/view-ticket/:bookingId', '/ticket', '/ticket/:pnr']) assert.ok(paths.includes(path), path);
  for (const [path, role] of [['/airline/*', 'ROLE_AIRLINE_OWNER'], ['/super-admin/*', 'ROLE_SYSTEM_ADMIN']]) {
    const wrapper = routes.find((route) => route.props.element?.props.roles?.includes(role));
    assert.ok(findAll(wrapper, (node) => node.props?.path === path).length);
  }
});
