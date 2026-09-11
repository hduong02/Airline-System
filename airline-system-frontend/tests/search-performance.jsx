// Local-only reproduction: real results screen, deterministic flights, no backend.
import React, { Profiler } from 'react';
import { createRoot } from 'react-dom/client';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import { MemoryRouter } from 'react-router-dom';
import SearchResults from '../src/pages/traveler/FlightList/SearchResults';
import flightSearch from '../src/Redux/flightSearch/flightSearchSlice';
import api from '../src/utils/api';
import '../src/index.css';

const airports = [{ id: 1, iataCode: 'HAN', name: 'Noi Bai', city: { name: 'Hanoi' } },
  { id: 2, iataCode: 'SGN', name: 'Tan Son Nhat', city: { name: 'Ho Chi Minh' } }];
const renderedFlights = new Set();
const flights = Array.from({ length: 20 }, (_, index) => ({
  id: index + 1, flightId: index + 1,
  get airlineName() { renderedFlights.add(index + 1); return 'Example Airline'; },
  flightNumber: `EX${index + 1}`,
  departureAirport: airports[0], arrivalAirport: airports[1], departureDateTime: '2026-10-22T08:00:00',
  arrivalDateTime: '2026-10-22T10:00:00', formattedDuration: '2h', availableSeats: 40,
  fare: { currentPrice: 1500, baseFare: 1500, cabinClass: 'ECONOMY', complimentaryMeals: true },
}));
api.defaults.adapter = async config => ({ config, status: 200, statusText: 'OK', headers: {},
  data: config.url.includes('/search') ? { content: flights, number: 0, last: true } : [],
});
const store = configureStore({ reducer: { flightSearch,
  airport: (state = { airports, loading: false }) => state,
  airline: (state = { dropdownAirlines: [] }) => state,
} });
const measurements = [];
const output = document.createElement('pre');
output.id = 'performance-report';
document.body.prepend(output);
createRoot(document.getElementById('root')).render(
  <Provider store={store}><MemoryRouter initialEntries={['/search-results?departureAirportId=1&arrivalAirportId=2&departureDate=2026-10-22']}>
    <Profiler id="results" onRender={(_id, phase, duration) => {
      measurements.push({ phase, duration: Math.round(duration * 100) / 100 });
      output.textContent = JSON.stringify({ renders: measurements }, null, 2);
    }}><SearchResults /></Profiler>
  </MemoryRouter></Provider>
);
const run = document.createElement('button');
run.textContent = 'Measure populated results';
document.body.prepend(run);
run.onclick = async () => {
  const before = measurements.length;
  renderedFlights.clear();
  // The first card's first button is its bookmark action.
  const heading = [...document.querySelectorAll('h3')].find(node => node.textContent === 'Example Airline');
  heading.closest('[data-slot="card"]').querySelector('button').click();
  await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  const interactionMs = Math.max(0, ...measurements.slice(before).map(item => item.duration));
  const cardsRendered = renderedFlights.size;
  const frames = [];
  let previous = performance.now();
  const started = previous;
  await new Promise(resolve => {
    const step = now => {
      frames.push(now - previous); previous = now;
      window.scrollTo(0, 700 + ((now - started) % 1000));
      if (now - started < 2000) requestAnimationFrame(step); else resolve();
    };
    requestAnimationFrame(step);
  });
  frames.sort((a, b) => a - b);
  const p95 = frames[Math.floor(frames.length * .95)];
  output.textContent = JSON.stringify({ verdict: p95 < 50 && interactionMs < 50 && cardsRendered === 1 ? 'PASS' : 'FAIL', interactionMs, cardsRendered,
    p95FrameMs: Math.round(p95 * 100) / 100, frames: frames.length, renders: measurements }, null, 2);
};
