import api from "@/utils/api";
import { createAsyncThunk } from "@reduxjs/toolkit";
import { clearSessionStorage } from '../sessionState.js';


// ✅ Signup
export const signup = createAsyncThunk(
  "auth/signup",
  async (userData, { rejectWithValue, getState, requestId, signal }) => {
    try {
      clearSessionStorage();
      const res = await api.post("/auth/signup", userData, { signal });
      // AuthResponse structure: { jwt, message, title, user }
      const authResponse = res.data;
      if (signal.aborted || getState().auth.authRequestId !== requestId) {
        return rejectWithValue('Authentication request is no longer active');
      }
      localStorage.setItem("jwt", authResponse.jwt);
      return authResponse;
    } catch (err) {
      return rejectWithValue(err.response?.data?.message || "Signup failed");
    }
  }
);

// ✅ Login
export const login = createAsyncThunk(
  "auth/login",
  async (credentials, { rejectWithValue, getState, requestId, signal }) => {
    try {
      clearSessionStorage();
      const res = await api.post("/auth/login", credentials, { signal });
      // AuthResponse structure: { jwt, message, title, user }
      const authResponse = res.data;
      if (signal.aborted || getState().auth.authRequestId !== requestId) {
        return rejectWithValue('Authentication request is no longer active');
      }
      localStorage.setItem("jwt", authResponse.jwt);

      return authResponse;
    } catch (err) {
      return rejectWithValue(err.response?.data?.message || "Login failed");
    }
  }
);

// ✅ Forgot Password
export const forgotPassword = createAsyncThunk(
  "auth/forgotPassword",
  async (email, { rejectWithValue }) => {
    try {
      const res = await api.post("/auth/forgot-password", { email });
      return res.data;
    } catch (err) {
      return rejectWithValue(err.response?.data?.message || "Failed to send reset email");
    }
  }
);

// ✅ Reset Password
export const resetPassword = createAsyncThunk(
  "auth/resetPassword",
  async ({ token, password }, { rejectWithValue }) => {
    try {
      const res = await api.post("/auth/reset-password", { token, password });
      return res.data;
    } catch (err) {
      return rejectWithValue(err.response?.data?.message || "Failed to reset password");
    }
  }
);
