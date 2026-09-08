import { createSlice } from "@reduxjs/toolkit";
import { searchFlightsAvailability } from "./flightSearchThunk.js";

const emptyPage = { content: [], number: 0, totalElements: 0, totalPages: 0, last: true };
const initialState = { searchResults: emptyPage, loading: false, loadingMore: false, error: null,
  lastSearchParams: null, activeRequestId: null, errorPage: null };
const flightSearchSlice = createSlice({
  name: "flightSearch", initialState,
  reducers: {
    clearSearchResults: () => initialState,
    invalidateSearchRequest: (state) => {
      state.activeRequestId = null;
      state.loading = false;
      state.loadingMore = false;
      state.error = null;
      state.errorPage = null;
    },
    setLastSearchParams: (state, action) => { state.lastSearchParams = action.payload; },
  },
  extraReducers: (builder) => builder
    .addCase(searchFlightsAvailability.pending, (state, action) => {
      state.activeRequestId = action.meta.requestId;
      state.loadingMore = (action.meta.arg.page || 0) > 0;
      state.loading = !state.loadingMore;
      state.error = null;
      state.errorPage = null;
    })
    .addCase(searchFlightsAvailability.fulfilled, (state, action) => {
      if (state.activeRequestId !== action.meta.requestId) return;
      const page = action.payload;
      state.searchResults = state.loadingMore
        ? { ...page, content: [...state.searchResults.content, ...page.content.filter(flight => !state.searchResults.content.some(existing => existing.id === flight.id))] }
        : page;
      state.loading = false;
      state.loadingMore = false;
      state.activeRequestId = null;
      state.lastSearchParams = action.meta.arg;
    })
    .addCase(searchFlightsAvailability.rejected, (state, action) => {
      if (state.activeRequestId !== action.meta.requestId) return;
      state.loading = false;
      state.loadingMore = false;
      state.activeRequestId = null;
      state.error = action.meta.aborted ? null : (action.payload || action.error.message || "Failed to search flights");
      state.errorPage = action.meta.aborted ? null : (action.meta.arg.page || 0);
    }),
});
export const { clearSearchResults, invalidateSearchRequest, setLastSearchParams } = flightSearchSlice.actions;
export default flightSearchSlice.reducer;
