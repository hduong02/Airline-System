import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadComponent, findAll } from './componentHarness.mjs';

const text = node => Array.isArray(node) ? node.map(text).join('') : typeof node === 'string' ? node : node && typeof node === 'object' ? text(node.props?.children) : '';

test('landing registration actions reach existing public routes and airline CTA appears initially', async () => {
  let destination;
  const page = await loadComponent('pages/Landing/LandingPage.jsx', { useNavigate: () => path => { destination = path; } });
  const checkButtons = tree => {
    for (const button of findAll(tree, node => node.type === 'Button' && /Get Started|Register Your Airline|Start Free Trial/.test(text(node)))) {
      button.props.onClick();
      assert.ok(destination === '/register' || destination.startsWith('/airline-onboarding'), `unexpected registration destination: ${destination}`);
    }
  };
  const initial = page.render();
  checkButtons(initial);
  const sections = findAll(initial, node => node.type === 'section');
  assert.equal(findAll(sections[0], node => node.type === 'Button' && text(node).includes('Register Your Airline')).length, 1);
  const airline = findAll(initial, node => node.type === 'Button' && text(node).includes('Register Your Airline'));
  assert.ok(airline.every(node => !node.props.className.includes('text-background')));
  findAll(initial, node => node.type === 'button' && node.props.className.includes('md:hidden'))[0].props.onClick();
  checkButtons(page.render());
});
