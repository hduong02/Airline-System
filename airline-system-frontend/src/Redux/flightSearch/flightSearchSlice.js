import { createSlice } from "@reduxjs/toolkit";
import { searchFlightsAvailability } from "./flightSearchThunk.js";

const emptyPage = { content: [], number: 0, totalElements: 0, totalPages: 0, last: true };
const initialState = { searchResults: emptyPage, loading: false, loadingMore: false, error: null,
  lastSearchParams: null, activeRequestId: null };
const flightSearchSlice = createSlice({
  name: "flightSearch", initialState,
  reducers: {
    clearSearchResults: () => initialState,
    setLastSearchParams: (state, action) => { state.lastSearchParams = action.payload; },
  },
  extraReducers: (builder) => builder
    .addCase(searchFlightsAvailability.pending, (state, action) => {
      state.activeRequestId = action.meta.requestId;
      state.loadingMore = (action.meta.arg.page || 0) > 0;
      state.loading = !state.loadingMore;
      if (!state.loadingMore) state.searchResults = emptyPage;
      state.error = null;
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
    }),
});
export const { clearSearchResults, setLastSearchParams } = flightSearchSlice.actions;
export default flightSearchSlice.reducer;
