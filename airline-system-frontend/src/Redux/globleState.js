import { configureStore, combineReducers } from "@reduxjs/toolkit";
import { createSessionMiddleware, isSessionBoundary } from './sessionState.js';
import authReducer from "./auth/authSlice.js";
import userReducer from "./user/userSlice.js";
import airlineReducer from "./airline/airlineSlice.js";
import aircraftReducer from "./aircraft/aircraftSlice.js";
import airportReducer from "./airport/airportSlice.js";
import flightReducer from "./flight/flightSlice.js";

import seatReducer from "./seat/seatSlice.js";
import seatMapReducer from "./SeatMap/seatMapSlice.js";
import cabinClassReducer from "./cabinClass/cabinClassSlice.js";
import cityReducer from "./city/citySlice.js";
import baggagePolicyReducer from "./baggagePolicy/baggagePolicySlice.js";
import bookingReducer from "./booking/bookingSlice.js";
import flightInstanceCabinReducer from "./flightInstanceCabin/flightInstanceCabinSlice.js";

import flightScheduleReducer from "./flightSchedule/flightScheduleSlice.js";
import flightInstanceReducer from "./flightInstance/flightInstanceSlice.js";
import fareRulesReducer from "./fareRules/fareRulesSlice.js";
import fareReducer from "./fare/fareSlice.js";
import ancillaryReducer from "./ancillary/ancillarySlice.js";
import flightCabinAncillaryReducer from "./flightCabinAncillary/flightCabinAncillarySlice.js";
import mealReducer from "./meal/mealSlice.js";
import flightMealReducer from "./flightMeal/flightMealSlice.js";
import paymentReducer from "./payment/paymentSlice.js";
import flightSearchReducer from "./flightSearch/flightSearchSlice.js";
import insuranceCoverageReducer from "./insuranceCoverage/insuranceCoverageSlice.js";
import couponReducer from "./coupon/couponSlice.js";

const combinedReducer = combineReducers({
    auth: authReducer,
    user: userReducer,

    // airline side
    airline: airlineReducer,
    aircraft: aircraftReducer,
    flight: flightReducer,

    seat: seatReducer,
    seatMap: seatMapReducer,
    cabinClass: cabinClassReducer,
    baggagePolicy: baggagePolicyReducer,
    booking: bookingReducer,
    payment: paymentReducer,
    flightInstanceCabin: flightInstanceCabinReducer,

    flightSchedule: flightScheduleReducer,
    flightInstance: flightInstanceReducer,
    flightSearch: flightSearchReducer,
    fareRules: fareRulesReducer,
    fare: fareReducer,
    ancillary: ancillaryReducer,
    flightCabinAncillary: flightCabinAncillaryReducer,
    meal: mealReducer,
    flightMeal: flightMealReducer,
    insuranceCoverage: insuranceCoverageReducer,
    coupon: couponReducer,

    // system admin side
    airport: airportReducer,
    city: cityReducer,
});

export function rootReducer(state, action) {
  if (isSessionBoundary(action) || action.type === 'user/logout/fulfilled') {
    const sessionId = (state?.auth.sessionId || 0) + 1;
    state = combinedReducer(undefined, { type: '@@session/reset' });
    state = { ...state, auth: { ...state.auth, initialized: true, sessionId } };
  }
  return combinedReducer(state, action);
}

export const createAppStore = () => configureStore({
  reducer: rootReducer,
  middleware: (getDefaultMiddleware) => getDefaultMiddleware().concat(createSessionMiddleware()),
  // Auth thunk arguments and responses contain credentials and tokens.
  devTools: false,
});
const globleState = createAppStore();

export default globleState;
