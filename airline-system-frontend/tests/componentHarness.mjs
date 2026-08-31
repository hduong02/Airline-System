// Exercises real component handlers/effects with controlled hooks and deferred API results.
// The production build checks JSX compilation; this harness does not test browser layout.
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const babel = createRequire(require.resolve('@vitejs/plugin-react'))('@babel/core');
const esbuild = createRequire(require.resolve('vite'))('esbuild');

export async function loadComponent(path, overrides = {}, exportName = 'default') {
  const source = await readFile(new URL(`../src/${path}`, import.meta.url), 'utf8');
  const names = [];
  const transformed = babel.transformSync(source, {
    configFile: false, babelrc: false, parserOpts: { plugins: ['jsx'] },
    plugins: [() => ({ visitor: {
      ImportDeclaration(path) {
        names.push(...path.node.specifiers.map((specifier) => specifier.local.name));
        path.remove();
      },
    } })],
  });
  const { code } = await esbuild.transform(transformed.code, { loader: 'jsx', format: 'cjs' });
  if (!names.includes('React')) names.push('React');
  const slots = [];
  const effects = [];
  let cursor = 0;
  const createElement = (type, props, ...children) => ({ type, props: { ...props, children } });
  const bindings = Object.fromEntries(names.map((name) => [name, name]));
  Object.assign(bindings, {
    React: { createElement }, motion: new Proxy({}, { get: (_, name) => `motion.${String(name)}` }),
    Motion: new Proxy({}, { get: (_, name) => `motion.${String(name)}` }),
    useState(initial) {
      const index = cursor++;
      if (!(index in slots)) slots[index] = typeof initial === 'function' ? initial() : initial;
      return [slots[index], (value) => { slots[index] = typeof value === 'function' ? value(slots[index]) : value; }];
    },
    useRef(initial) {
      const index = cursor++;
      if (!(index in slots)) slots[index] = { current: initial };
      return slots[index];
    },
    useEffect(callback, deps) {
      const index = cursor++;
      const previous = slots[index];
      if (!previous || deps.some((value, i) => !Object.is(value, previous.deps[i]))) {
        effects.push(() => {
          previous?.cleanup?.();
          slots[index] = { deps, cleanup: callback() };
        });
      }
    },
    cn: (...values) => values.filter(Boolean).join(' '),
    ...overrides,
  });
  Object.assign(bindings.React, {
    useState: bindings.useState, useRef: bindings.useRef, useEffect: bindings.useEffect,
  });
  const module = { exports: {} };
  const Component = new Function('bindings', 'module', 'exports', 'sessionStorage', 'exportName',
    `const { ${names.join(', ')} } = bindings;\n${code}\nreturn module.exports[exportName];`)(
    bindings, module, module.exports, overrides.sessionStorage, exportName,
  );
  return {
    render(props = {}) { cursor = 0; return Component(props); },
    flushEffects() { effects.splice(0).forEach((run) => run()); },
    unmount() { slots.forEach((slot) => slot?.cleanup?.()); },
  };
}

export function findAll(node, predicate) {
  if (Array.isArray(node)) return node.flatMap((child) => findAll(child, predicate));
  if (!node || typeof node !== 'object') return [];
  return [...(predicate(node) ? [node] : []), ...findAll(node.props?.children, predicate)];
}

export const deferred = () => {
  let resolve, reject;
  const promise = new Promise((success, failure) => { resolve = success; reject = failure; });
  return { promise, resolve, reject };
};
export const settle = () => new Promise((resolve) => setImmediate(resolve));
