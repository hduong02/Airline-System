import { createAsyncThunk } from "@reduxjs/toolkit";
import api from "@/utils/api";
import { getHeaders } from "@/utils/getHeaders";

const API_URL = "/api/payments";

export const getAirlinePayments = createAsyncThunk(
  'payment/getAirline',
  async ({ page = 0, size = 20, sortBy = 'createdAt', sortDirection = 'DESC',
    search = '', status = 'all', gateway = 'all' } = {}, { rejectWithValue, signal }) => {
    try {
      const params = new URLSearchParams({ page: String(page), size: String(size), sortBy, sortDirection });
      if (search.trim()) params.set('search', search.trim());
      if (status !== 'all') params.set('status', status);
      if (gateway !== 'all') params.set('gateway', gateway);
      const response = await api.get(`/api/bookings/transactions/airline?${params}`, {
        headers: getHeaders(), signal,
      });
      return response.data;
    } catch (error) {
      return rejectWithValue(error.response?.data?.message || error.response?.data?.detail || 'Failed to fetch airline transactions');
    }
  },
);




// ---------- VERIFY PAYMENT ----------
/**
 * Verify payment after gateway callback
 * POST /api/payments/verify
 */
export const verifyPayment = createAsyncThunk(
  "payment/verify",
  async (request, { rejectWithValue }) => {
  console.log("Verifying payment thunk called with request: ---------- ", request);
    try {
      console.log("📤 Verifying payment with data:", request);
      const res = await api.post(`${API_URL}/verify`, request, {
        headers: getHeaders()
      });
      console.log("✅ verifyPayment success:", res.data);
      return res.data;
    } catch (err) {
      console.error("❌ verifyPayment error:", err.response?.data || err.message);
      return rejectWithValue(err.response?.data?.message || "Failed to verify payment");
    }
  }
);





// ---------- GET ALL PAYMENTS ----------
/**
 * Get all payments (Admin only)
 * GET /api/payments
 */
export const getAllPayments = createAsyncThunk(
  "payment/getAll",
  async ({ page = 0, size = 20, sortBy = "createdAt", sortDirection = "DESC" } = {}, { rejectWithValue }) => {
    try {
      const params = new URLSearchParams({
        page: page.toString(),
        size: size.toString(),
        sortBy,
        sortDirection
      });

      console.log("📤 Fetching all payments with params:", { page, size, sortBy, sortDirection });
      const res = await api.get(`${API_URL}?${params.toString()}`, {
        headers: getHeaders()
      });
      console.log("✅ getAllPayments success:", res.data);
      return res.data;
    } catch (err) {
      console.error("❌ getAllPayments error:", err.response?.data || err.message);
      return rejectWithValue(err.response?.data?.message || "Failed to fetch payments");
    }
  }
);





