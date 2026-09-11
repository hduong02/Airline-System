// Local-only regression fixture using the real modal, thunks and fare cards.
import React, { Profiler } from 'react';
import { createRoot } from 'react-dom/client';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import { MemoryRouter } from 'react-router-dom';
import FlightPricingModal from '../src/pages/traveler/FlightList/FlightPricing/FlightPricingModal';
import api from '../src/utils/api';
import '../src/index.css';

const renderedFares = new Set();
const rebuiltBenefits = new Set();
const fares = Array.from({ length: 12 }, (_, index) => ({
  id: index + 1, flightId: 1, cabinClassId: 1, cabinClass: 'ECONOMY',
  get fareLabel() { renderedFares.add(index + 1); return `Fare ${index + 1}`; },
  baseFare: 100 + index * 20, taxesAndFees: 20, airlineFees: 10,
  get fullRefund() { rebuiltBenefits.add(index + 1); return true; },
  freeDateChange: true, complimentaryMeals: true,
  complimentaryBeverages: true, premiumMealChoice: true, extraSeatSpace: true,
  advanceSeatSelection: true, priorityBoarding: true, priorityCheckin: true,
  loungeAccess: true, inFlightInternet: true, inFlightEntertainment: true,
  baggagePolicy: { freeCheckedBagsAllowance: 1, checkInBaggageWeightPerPiece: 20,
    cabinBaggagePieces: 1, cabinBaggageMaxWeight: 7 },
  fareRules: { isRefundable: true, changeFee: 0 },
}));
const requests = [];
api.defaults.adapter = async config => {
  requests.push(config.url);
  return { config, status: 200, statusText: 'OK', headers: {},
    data: config.url.startsWith('/api/fares/') ? fares : [{ id: 1, code: 'ECONOMY', name: 'Economy' }],
  };
};
const store = configureStore({ reducer: (state = {}) => state });
const output = document.getElementById('performance-report');
let measuring = false;
let durations = [];
document.addEventListener('click', event => {
  if (!event.target.closest('button')?.textContent.includes('Select This Fare')) return;
  measuring = true;
  durations = [];
  renderedFares.clear();
  rebuiltBenefits.clear();
  requestAnimationFrame(() => requestAnimationFrame(() => {
    measuring = false;
    const interactionMs = Math.round(durations.reduce((sum, value) => sum + value, 0) * 100) / 100;
    const cardsRendered = renderedFares.size;
    const selected = [...document.querySelectorAll('h3')]
      .filter(node => node.closest('.group')?.textContent.includes('SELECTED')).map(node => node.textContent);
    const canContinue = [...document.querySelectorAll('button')]
      .some(button => button.textContent.includes('Continue to Booking') && !button.disabled);
    output.textContent = JSON.stringify({
      verdict: cardsRendered > 0 && cardsRendered <= 2 && rebuiltBenefits.size === 0 && interactionMs < 50 && requests.length === 2 && selected.length === 1 && canContinue ? 'PASS' : 'FAIL',
      interactionMs, cardsRendered, rebuiltBenefits: rebuiltBenefits.size, requests: requests.length,
      selected, canContinue,
    }, null, 2);
  }));
}, true);
const flight = { id: 1, flightId: 1, aircraftId: 1, cabinClass: 'ECONOMY', airlineName: 'Example Airline', flightNumber: 'EX1',
  departureAirport: { iataCode: 'HAN' }, arrivalAirport: { iataCode: 'SGN' },
  departureDateTime: '2026-10-22T08:00:00', arrivalDateTime: '2026-10-22T10:00:00' };
createRoot(document.getElementById('root')).render(
  <Provider store={store}><MemoryRouter initialEntries={['/?numberOfTravellers=2&cabinClass=ECONOMY']}>
    <Profiler id="fares" onRender={(_id, _phase, duration) => { if (measuring) durations.push(duration); }}>
      <FlightPricingModal isOpen flight={flight} onClose={() => {}} />
    </Profiler>
  </MemoryRouter></Provider>
);
