import { createSlice } from '@reduxjs/toolkit';
import {
  getUserProfile,
 
  getAllUsers,
  getUserById,
  logout
} from './userThunks';

const initialState = {
  userProfile: null,
  users: [],
  usersTotalElements: 0,
  usersTotalPages: 0,
  usersRequestId: null,
  customers: [],
  cashiers: [],
  selectedUser: null,
  usersLoading: false,
  loading: false,
  error: null,
};

const userSlice = createSlice({
  name: 'user',
  initialState,
  reducers: {
    clearUserState: (state) => {
      state.userProfile = null;
      state.selectedUser = null;
      state.users = [];
      state.usersTotalElements = 0;
      state.usersTotalPages = 0;
      state.usersRequestId = null;
      state.customers = [];
      state.cashiers = [];
      state.error = null;
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(getUserProfile.pending, (state) => { state.loading = true; })
      .addCase(getUserProfile.fulfilled, (state, action) => {
        state.loading = false;
        state.userProfile = action.payload;
      })
      .addCase(getUserProfile.rejected, (state, action) => {
        state.loading = false;
        state.error = action.payload;
      })

      

      .addCase(getAllUsers.pending, (state, action) => {
        state.usersLoading = true;
        state.usersRequestId = action.meta.requestId;
        state.error = null;
      })
      .addCase(getAllUsers.fulfilled, (state, action) => {
        if (state.usersRequestId !== action.meta.requestId) return;
        state.usersLoading = false;
        state.users = action.payload.content;
        state.usersTotalElements = action.payload.totalElements;
        state.usersTotalPages = action.payload.totalPages;
      })
      .addCase(getAllUsers.rejected, (state, action) => {
        if (state.usersRequestId !== action.meta.requestId) return;
        state.usersLoading = false;
        if (!action.meta.aborted) state.error = action.payload;
      })

      .addCase(getUserById.fulfilled, (state, action) => {
        state.selectedUser = action.payload;
      })
      .addCase(logout.fulfilled, (state) => {
        state.userProfile = null;
        state.selectedUser = null;
        state.error = null;
      })

      .addMatcher(
        (action) => action.type.startsWith('user/') && action.type.endsWith('/rejected') && action.type !== getAllUsers.rejected.type,
        (state, action) => {
          state.error = action.payload;
        }
      );
  },
});

export const { clearUserState } = userSlice.actions;
export default userSlice.reducer;
