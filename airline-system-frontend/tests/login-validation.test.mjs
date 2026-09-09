import assert from 'node:assert/strict';
import { test } from 'node:test';
import * as Yup from 'yup';
import { loadComponent } from './componentHarness.mjs';
import { getLoginDestination } from '../src/utils/authNavigation.js';

async function loginForm(result = {
  type: 'auth/login/fulfilled', payload: { user: { role: 'ROLE_SYSTEM_ADMIN' } },
}) {
  const dispatched = [];
  const navigated = [];
  const login = (credentials) => ({ type: 'login-request', credentials });
  login.fulfilled = { match: (action) => action.type === 'auth/login/fulfilled' };
  const component = await loadComponent('pages/auth/LoginForm.jsx', {
    Yup, login, getLoginDestination,
    useDispatch: () => async (action) => { dispatched.push(action); return result; },
    useSelector: (select) => select({ auth: { loading: false, error: null } }),
    useNavigate: () => (...args) => navigated.push(args),
    useLocation: () => ({ state: null }),
  });
  return { form: component.render(), dispatched, navigated };
}

test('existing admin can submit a five-character password and reach the admin dashboard', async () => {
  const { form, dispatched, navigated } = await loginForm();
  const credentials = { email: 'admin@example.test', password: 'a1b2c' };
  const validated = await form.props.validationSchema.validate(credentials);
  const submitting = [];
  await form.props.onSubmit(validated, { setSubmitting: (value) => submitting.push(value) });

  assert.deepEqual(dispatched, [{ type: 'login-request', credentials }]);
  assert.deepEqual(navigated, [['/super-admin', { replace: true }]]);
  assert.deepEqual(submitting, [true, false]);
});

test('login accepts non-empty passwords without changing their characters', async () => {
  const { form } = await loginForm();
  for (const password of ['x', 'a1b2c', ' a1b ', 'longer-password']) {
    const credentials = { email: 'existing@example.test', password };
    assert.deepEqual(await form.props.validationSchema.validate(credentials), credentials);
  }
});

test('login still requires a password', async () => {
  const { form } = await loginForm();
  await assert.rejects(form.props.validationSchema.validate({ email: 'admin@example.test', password: '' }),
    /Password is required/);
});

test('login still requires a valid email', async () => {
  const { form } = await loginForm();
  await assert.rejects(form.props.validationSchema.validate({ email: '', password: 'a1b2c' }),
    /Email is required/);
  await assert.rejects(form.props.validationSchema.validate({ email: 'invalid', password: 'a1b2c' }),
    /Invalid email format/);
});

test('server-rejected credentials do not navigate to a dashboard', async () => {
  const { form, navigated } = await loginForm({ type: 'auth/login/rejected' });
  const credentials = { email: 'admin@example.test', password: 'a1b2c' };
  await form.props.validationSchema.validate(credentials);
  await form.props.onSubmit(credentials, { setSubmitting: () => {} });
  assert.deepEqual(navigated, []);
});

test('registration retains its minimum password length', async () => {
  const component = await loadComponent('pages/auth/RegisterForm.jsx', {
    Yup, useDispatch: () => () => {}, useNavigate: () => () => {},
    useSelector: (select) => select({ auth: { loading: false, error: null } }),
  });
  const credentials = { fullName: 'Test User', email: 'new@example.test', mobile: '0123456789' };
  const { validationSchema } = component.render().props;
  await assert.rejects(validationSchema.validate({ ...credentials, password: 'a1b2c' }),
    /Password must be at least 6 characters/);
  assert.equal((await validationSchema.validate({ ...credentials, password: 'a1b2c3' })).password, 'a1b2c3');
});
