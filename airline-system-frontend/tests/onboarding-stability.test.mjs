import assert from 'node:assert/strict';
import { test } from 'node:test';
import * as Yup from 'yup';
import { loadComponent, findAll } from './componentHarness.mjs';

test('wizard has no continuously animated or filtered background layers', async () => {
  const wizard = await loadComponent('pages/Onboarding/AirlineOnboardingWizard.jsx', {
    readOnboardingProgress: () => ({ currentStep: 1, formData: {} }),
  });
  const tree = wizard.render();
  const expensiveLayers = findAll(tree, node => {
    const classes = node.props?.className || '';
    return /(?:^|\s)(?:animate-pulse|blur-\S+|backdrop-blur-\S+)(?:\s|$)/.test(classes);
  });
  assert.equal(expensiveLayers.length, 0,
    'the wizard should remain idle without animated blur layers behind the form');
});

test('password strength updates retain input component identity', async () => {
  const owner = await loadComponent('pages/Onboarding/steps/OwnerDetailsStep.jsx', {
    Yup, useDispatch: () => () => {}, useSelector: () => ({ user: null, isAuthenticated: false }),
  });
  const props = { data: {}, onDataChange() {}, onNext() {} };
  const fields = () => {
    const formik = findAll(owner.render(props), node => node.type === 'Formik')[0];
    return findAll(formik.props.children[0]({ isValid: true }), node => typeof node.type === 'function' && node.props.name);
  };
  const before = fields();
  const password = before.find(node => node.props.name === 'password');
  const field = findAll(password.type(password.props), node => node.type === 'Field')[0];
  const input = findAll(field.props.children[0]({ field: { onChange() {} }, meta: {} }), node => node.type === 'Input')[0];
  input.props.onChange({ target: { value: 'a' } });
  const after = fields();
  for (const previous of before) {
    assert.equal(after.find(node => node.props.name === previous.props.name).type, previous.type,
      `${previous.props.name} must not remount when password strength changes`);
  }
});

for (const step of ['AirlineDetailsStep', 'SupportContactStep']) {
  test(`${step} field components survive parent renders`, async () => {
    const component = await loadComponent(`pages/Onboarding/steps/${step}.jsx`, {
      Yup, useDispatch: () => () => {}, useSelector: () => ({ cities: [] }),
    });
    const props = { data: {}, onDataChange() {}, onNext() {}, onPrevious() {} };
    const fields = () => {
      const formik = findAll(component.render(props), node => node.type === 'Formik')[0];
      return findAll(formik.props.children[0]({ values: {}, setFieldValue() {}, isValid: true }), node => typeof node.type === 'function' && node.props.name);
    };
    const before = fields();
    assert.ok(before.length > 0);
    const after = fields();
    for (const previous of before) {
      assert.equal(after.find(node => node.props.name === previous.props.name).type, previous.type,
        `${previous.props.name} must not remount on parent render`);
    }
  });
}
