import { createSlice } from "@reduxjs/toolkit";
import {
  createFlightSchedule,
  getFlightScheduleById,
  getAllFlightSchedules,
  getFlightSchedulePage,
  updateFlightSchedule,
  deleteFlightSchedule
} from "./flightScheduleThunk.js";

const initialState = {
  managementPage: null,
  managementPageLoading: false,
  managementPageError: null,
  managementPageRequestId: null,
  // Data
  flightSchedules: [],
  listLoaded: false,
  currentFlightSchedule: null,
  paginatedFlightSchedules: {
    content: [],
    totalElements: 0,
    totalPages: 0,
    size: 10,
    number: 0,
    first: true,
    last: false,
    numberOfElements: 0
  },

  // UI State
  loading: false,
  error: null,

  // Filters/Search
  searchKeyword: "",
  currentPage: 0,
  pageSize: 10,
  sortBy: "departureAirportId",
  sortDirection: "asc",

  // Specific loading states
  createLoading: false,
  updateLoading: false,
  deleteLoading: false,
  searchLoading: false
};

const flightScheduleSlice = createSlice({
  name: "flightSchedule",
  initialState,
  reducers: {
    clearFlightScheduleState: (state) => {
      state.managementPage = null;
      state.managementPageLoading = false;
      state.managementPageError = null;
      state.managementPageRequestId = null;
      state.flightSchedules = [];
      state.listLoaded = false;
      state.currentFlightSchedule = null;
      state.paginatedFlightSchedules = initialState.paginatedFlightSchedules;
      state.error = null;
    },
    clearCurrentFlightSchedule: (state) => {
      state.currentFlightSchedule = null;
    },
    setSearchKeyword: (state, action) => {
      state.searchKeyword = action.payload;
    },
    setCurrentPage: (state, action) => {
      state.currentPage = action.payload;
    },
    setPageSize: (state, action) => {
      state.pageSize = action.payload;
    },
    setSortBy: (state, action) => {
      state.sortBy = action.payload;
    },
    setSortDirection: (state, action) => {
      state.sortDirection = action.payload;
    }
  },
  extraReducers: (builder) => {
    builder
      .addCase(getFlightSchedulePage.pending, (state, action) => {
        state.managementPageLoading = true;
        state.managementPageError = null;
        state.managementPageRequestId = action.meta.requestId;
      })
      .addCase(getFlightSchedulePage.fulfilled, (state, action) => {
        if (state.managementPageRequestId !== action.meta.requestId) return;
        state.managementPageLoading = false;
        state.managementPageRequestId = null;
        state.managementPage = action.payload;
      })
      .addCase(getFlightSchedulePage.rejected, (state, action) => {
        if (state.managementPageRequestId !== action.meta.requestId) return;
        state.managementPageLoading = false;
        state.managementPageRequestId = null;
        state.managementPageError = action.meta.aborted ? null : action.payload || action.error.message;
      });
    // ---------- CREATE ----------
    builder
      .addCase(createFlightSchedule.pending, (state) => {
        state.loading = true;
        state.createLoading = true;
        state.error = null;
      })
      .addCase(createFlightSchedule.fulfilled, (state, action) => {
        state.loading = false;
        state.createLoading = false;
        state.currentFlightSchedule = action.payload;
        state.flightSchedules.unshift(action.payload);
      })
      .addCase(createFlightSchedule.rejected, (state, action) => {
        state.loading = false;
        state.createLoading = false;
        state.error = action.payload;
      });

    // ---------- GET BY ID ----------
    builder
      .addCase(getFlightScheduleById.pending, (state) => {
        state.loading = true;
        state.error = null;
      })
      .addCase(getFlightScheduleById.fulfilled, (state, action) => {
        state.loading = false;
        state.currentFlightSchedule = action.payload;
      })
      .addCase(getFlightScheduleById.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload;
      });

    // ---------- GET ALL ----------
    builder
      .addCase(getAllFlightSchedules.pending, (state) => {
        state.listLoaded = false;
        state.loading = true;
        state.error = null;
      })
      .addCase(getAllFlightSchedules.fulfilled, (state, action) => {
        state.listLoaded = true;
        state.loading = false;
        state.flightSchedules = action.payload.content || action.payload;
        state.paginatedFlightSchedules = Array.isArray(action.payload)
          ? { ...initialState.paginatedFlightSchedules, content: action.payload,
              totalElements: action.payload.length, numberOfElements: action.payload.length,
              totalPages: action.payload.length ? 1 : 0 }
          : action.payload;
      })
      .addCase(getAllFlightSchedules.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload;
      });

    // ---------- UPDATE ----------
    builder
      .addCase(updateFlightSchedule.pending, (state) => {
        state.loading = true;
        state.updateLoading = true;
        state.error = null;
      })
      .addCase(updateFlightSchedule.fulfilled, (state, action) => {
        state.loading = false;
        state.updateLoading = false;
        state.currentFlightSchedule = action.payload;
        const index = state.flightSchedules.findIndex(fs => fs.id === action.payload.id);
        if (index !== -1) state.flightSchedules[index] = action.payload;
        const paginatedIndex = state.paginatedFlightSchedules.content.findIndex(fs => fs.id === action.payload.id);
        if (paginatedIndex !== -1) state.paginatedFlightSchedules.content[paginatedIndex] = action.payload;
      })
      .addCase(updateFlightSchedule.rejected, (state, action) => {
        state.loading = false;
        state.updateLoading = false;
        state.error = action.payload;
      });

    // ---------- DELETE ----------
    builder
      .addCase(deleteFlightSchedule.pending, (state) => {
        state.loading = true;
        state.deleteLoading = true;
        state.error = null;
      })
      .addCase(deleteFlightSchedule.fulfilled, (state, action) => {
        state.loading = false;
        state.deleteLoading = false;
        state.flightSchedules = state.flightSchedules.filter(fs => fs.id !== action.payload);
        state.paginatedFlightSchedules.content = state.paginatedFlightSchedules.content.filter(fs => fs.id !== action.payload);
        if (state.paginatedFlightSchedules.numberOfElements > 0) state.paginatedFlightSchedules.numberOfElements -= 1;
      })
      .addCase(deleteFlightSchedule.rejected, (state, action) => {
        state.loading = false;
        state.deleteLoading = false;
        state.error = action.payload;
      });

    // Global error matcher
    builder.addMatcher(
      (action) => action.type.startsWith("flightSchedule/") && action.type.endsWith("/rejected")
        && action.type !== getFlightSchedulePage.rejected.type,
      (state, action) => {
        state.error = action.payload;
      }
    );
  }
});

export const {
  clearFlightScheduleState,
  clearCurrentFlightSchedule,
  setSearchKeyword,
  setCurrentPage,
  setPageSize,
  setSortBy,
  setSortDirection
} = flightScheduleSlice.actions;

export default flightScheduleSlice.reducer;
