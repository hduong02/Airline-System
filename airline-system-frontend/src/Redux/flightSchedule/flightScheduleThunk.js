import { createAsyncThunk } from "@reduxjs/toolkit";
import api from "@/utils/api";
import { getHeaders } from "@/utils/getHeaders";

const API_URL = "/api/flight-schedules";

// ---------- CREATE ----------
export const createFlightSchedule = createAsyncThunk(
  "flightSchedule/create",
  async (data, { rejectWithValue }) => {
    try {
      const res = await api.post(API_URL, data, { headers: getHeaders() });
      console.log("✅ createFlightSchedule success:", res.data);
      return res.data;
    } catch (err) {
      console.error("❌ createFlightSchedule error:", 
        err.response);
      return rejectWithValue(err.response?.data?.message || "Failed to create flight schedule");
    }
  }
);

// ---------- GET BY ID ----------
export const getFlightScheduleById = createAsyncThunk(
  "flightSchedule/getById",
  async (id, { rejectWithValue }) => {
    try {
      const res = await api.get(`${API_URL}/${id}`, { headers: getHeaders() });
      console.log("✅ getFlightScheduleById success:", res.data);
      return res.data;
    } catch (err) {
      console.error("❌ getFlightScheduleById error:", err.response?.data?.message || err.message);
      return rejectWithValue(err.response?.data?.message || "Flight schedule not found");
    }
  }
);

// ---------- GET ALL ----------
export const getAllFlightSchedules = createAsyncThunk(
  "flightSchedule/getAll",
  async (_, { rejectWithValue, signal }) => {
    try {
      const res = await api.get(API_URL, { headers: getHeaders(), signal });
      console.log("✅ getAllFlightSchedules success:", res.data);
      return res.data;
    } catch (err) {
      console.error("❌ getAllFlightSchedules error:", err.response?.data?.message || err.message);
      return rejectWithValue(err.response?.data?.message || "Failed to fetch flight schedules");
    }
  }
);

// One bounded page for management; keep the legacy list separate.
export const getFlightSchedulePage = createAsyncThunk(
  "flightSchedule/getPage",
  async ({ page = 0, size = 20 } = {}, { rejectWithValue, signal }) => {
    try {
      const { data } = await api.get(`${API_URL}/page`, {
        headers: getHeaders(), signal, params: { page, size },
      });
      if (!Array.isArray(data?.content) || !Number.isInteger(data.number)
          || !Number.isInteger(data.totalElements) || data.totalElements < 0
          || !Number.isInteger(data.totalPages) || data.totalPages < 0) {
        throw new Error("Invalid schedule page response");
      }
      return data;
    } catch (err) {
      return rejectWithValue(err.response?.data?.message || err.message || "Failed to fetch flight schedules");
    }
  }
);

// ---------- UPDATE ----------
export const updateFlightSchedule = createAsyncThunk(
  "flightSchedule/update",
  async ({ id, data }, { rejectWithValue }) => {
    try {
      const res = await api.put(`${API_URL}/${id}`, data, { headers: getHeaders() });
      console.log("✅ updateFlightSchedule success:", res.data);
      return res.data;
    } catch (err) {
      console.error("❌ updateFlightSchedule error:", err.response?.data?.message || err.message);
      return rejectWithValue(err.response?.data?.message || "Failed to update flight schedule");
    }
  }
);

// ---------- DELETE ----------
export const deleteFlightSchedule = createAsyncThunk(
  "flightSchedule/delete",
  async (id, { rejectWithValue }) => {
    try {
      await api.delete(`${API_URL}/${id}`, { headers: getHeaders() });
      console.log("✅ deleteFlightSchedule success:", id);
      return id;
    } catch (err) {
      console.error("❌ deleteFlightSchedule error:", err.response?.data?.message || err.message);
      return rejectWithValue(err.response?.data?.message || "Failed to delete flight schedule");
    }
  }
);

