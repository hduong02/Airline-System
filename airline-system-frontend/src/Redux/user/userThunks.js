import { createAsyncThunk } from "@reduxjs/toolkit";
import api from "@/utils/api";
import { getHeaders } from "@/utils/getHeaders";
import { clearSessionStorage } from '../sessionState.js';

// 🔹 Get user profile from JWT
export const getUserProfile = createAsyncThunk(
  "user/getProfile",
  async (token, { rejectWithValue, signal, getState, requestId, dispatch }) => {
    try {
      const res = await api.get("/api/users/profile", {
        headers: { Authorization: `Bearer ${token}` },
        signal,
      });

      return res.data;
    } catch (err) {
      if (!signal.aborted && getState().auth.profileRequestId === requestId) {
        clearSessionStorage();
        dispatch({ type: 'auth/sessionExpired' });
      }
      return rejectWithValue(
        err.response?.data?.message || "Failed to fetch profile"
      );
    }
  },
  { condition: (token) => Boolean(token) && token === localStorage.getItem('jwt') }
);



// 🔹 Get all users (super admin)
export const getAllUsers = createAsyncThunk(
  "user/getAll",
  async (_, { rejectWithValue }) => {
    try {
      const res = await api.get("/api/users", { headers: getHeaders() });
      return res.data;
    } catch (err) {
      return rejectWithValue(
        err.response?.data?.message || "Failed to fetch users"
      );
    }
  }
);

// 🔹 Get user by ID
export const getUserById = createAsyncThunk(
  "user/getById",
  async (userId, { rejectWithValue }) => {
    try {
      const res = await api.get(`/users/${userId}`);
      return res.data;
    } catch (err) {
      return rejectWithValue(err.response?.data?.message || "User not found");
    }
  }
);

// 🔹 Logout user
export const logout = createAsyncThunk(
  "user/logout",
  async (_, { rejectWithValue }) => {
    try {
      clearSessionStorage();
      return "Logged out successfully";
    } catch (err) {
      return rejectWithValue(err.message || "Failed to logout");
    }
  }
);
