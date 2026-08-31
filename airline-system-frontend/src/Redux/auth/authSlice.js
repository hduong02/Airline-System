import { createSlice } from "@reduxjs/toolkit";
import { login, signup, forgotPassword, resetPassword } from "./authThunk";
import { getUserProfile, logout } from "../user/userThunks";

// ✅ Auth Slice
const authSlice = createSlice({
  name: "auth",
  initialState: {
    user: null,
    loading: false,
    error: null,
    isAuthenticated: false,
    initialized: false,
    profileRequestId: null,
    authRequestId: null,
    sessionId: 0,
    forgotPasswordLoading: false,
    forgotPasswordError: null,
    forgotPasswordSuccess: false,
    resetPasswordLoading: false,
    resetPasswordError: null,
    resetPasswordSuccess: false,
  },
  reducers: {
    initializeAnonymous: (state) => { state.initialized = true; },
    sessionExpired: (state) => {
      state.user = null;
      state.isAuthenticated = false;
      state.initialized = true;
      state.profileRequestId = null;
      state.authRequestId = null;
      state.loading = false;
    },
    clearForgotPasswordState: (state) => {
      state.forgotPasswordLoading = false;
      state.forgotPasswordError = null;
      state.forgotPasswordSuccess = false;
    },
    clearResetPasswordState: (state) => {
      state.resetPasswordLoading = false;
      state.resetPasswordError = null;
      state.resetPasswordSuccess = false;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(signup.pending, (state, action) => {
        state.authRequestId = action.meta.requestId;
        state.loading = true;
        state.error = null;
      })
      .addCase(signup.fulfilled, (state, action) => {
        if (action.meta && state.authRequestId !== action.meta.requestId) return;
        state.loading = false;
        state.user = action.payload.user;
        state.isAuthenticated = true;
        state.initialized = true;
        state.authRequestId = null;
      })
      .addCase(signup.rejected, (state, action) => {
        if (action.meta && state.authRequestId !== action.meta.requestId) return;
        state.authRequestId = null;
        state.loading = false;
        state.error = action.payload;
      })

      .addCase(login.pending, (state, action) => {
        state.authRequestId = action.meta.requestId;
        state.loading = true;
        state.error = null;
      })
      .addCase(login.fulfilled, (state, action) => {
        if (action.meta && state.authRequestId !== action.meta.requestId) return;
        state.loading = false;
        state.user = action.payload.user; // Extract user from AuthResponse
        state.isAuthenticated = true;
        state.initialized = true;
        state.authRequestId = null;
      })
      .addCase(login.rejected, (state, action) => {
        if (action.meta && state.authRequestId !== action.meta.requestId) return;
        state.authRequestId = null;
        state.loading = false;
        state.error = action.payload;
      })

      // Forgot Password cases
      .addCase(forgotPassword.pending, (state) => {
        state.loading = true;
        state.forgotPasswordLoading = true;
        state.forgotPasswordError = null;
        state.forgotPasswordSuccess = false;
      })
      .addCase(forgotPassword.fulfilled, (state) => {
        state.loading = false;
        state.forgotPasswordLoading = false;
        state.forgotPasswordSuccess = true;
        state.forgotPasswordError = null;
      })
      .addCase(forgotPassword.rejected, (state, action) => {
        state.loading = false;
        state.forgotPasswordLoading = false;
        state.forgotPasswordError = action.payload;
        state.forgotPasswordSuccess = false;
        state.error = action.payload;
      })

      // Reset Password cases
      .addCase(resetPassword.pending, (state) => {
        state.loading = true;
        state.resetPasswordLoading = true;
        state.resetPasswordError = null;
        state.resetPasswordSuccess = false;
      })
      .addCase(resetPassword.fulfilled, (state) => {
        state.loading = false;
        state.resetPasswordLoading = false;
        state.resetPasswordSuccess = true;
        state.resetPasswordError = null;
      })
      .addCase(resetPassword.rejected, (state, action) => {
        state.loading = false;
        state.resetPasswordLoading = false;
        state.resetPasswordError = action.payload;
        state.resetPasswordSuccess = false;
        state.error = action.payload;
      })
      // Get User Profile to maintain auth state
      .addCase(getUserProfile.pending, (state, action) => {
        state.profileRequestId = action.meta.requestId;
        if (!state.isAuthenticated) state.initialized = false;
      })
      .addCase(getUserProfile.fulfilled, (state, action) => {
        if (state.profileRequestId !== action.meta.requestId) return;
        state.loading = false;
        state.isAuthenticated = true;
        state.user = action.payload;
        state.initialized = true;
        state.profileRequestId = null;
      })
      .addCase(getUserProfile.rejected, (state, action) => {
        if (state.profileRequestId !== action.meta.requestId) return;
        state.loading = false;
        state.error = action.payload;
        state.isAuthenticated = false;
        state.user = null;
        state.initialized = true;
        state.profileRequestId = null;
      })

      .addCase(logout.pending, (state) => {
        state.user = null;
        state.isAuthenticated = false;
        state.error = null;
        state.initialized = true;
        state.authRequestId = null;
        state.profileRequestId = null;
      });
  },
});

export const { initializeAnonymous, sessionExpired, clearForgotPasswordState, clearResetPasswordState } =
  authSlice.actions;
export default authSlice.reducer;
