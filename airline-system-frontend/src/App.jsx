import "./App.css";
import {
  BrowserRouter as Router,
  Routes,
  Route,
} from "react-router-dom";
import { ThemeProvider } from "./components/theme-provider.jsx";
import { Toaster } from "./components/ui/sonner.jsx";
import LandingPage from "./pages/Landing/LandingPage.jsx";
import Header from "./pages/traveler/Home/Header.jsx";
import HomePage from "./pages/traveler/Home/HomePage.jsx";
import SearchResults from "./pages/traveler/FlightList/SearchResults.jsx";

import PaymentPage from "./pages/traveler/Payment/PaymentPage.jsx";
import BookingHistory from "./pages/traveler/BookingHistory/BookingHistory.jsx";
import BookingSuccess from "./pages/traveler/BookingSuccess/BookingSuccess.jsx";
import ETicket from "./pages/traveler/Ticket/ETicket.jsx";
import Ticket from "./pages/traveler/Ticket/Ticket.jsx";
import UserProfile from "./pages/traveler/Profile/UserProfile.jsx";
import AirlineDashboard from "./pages/airline/Dashboard/AirlineDashboard.jsx";
import SuperAdminDashboard from "./pages/super-admin/Dashboard/SuperAdminDashboard.jsx";


import ForgotPassword from "./pages/auth/ForgotPassword.jsx";
import ResetPassword from "./pages/auth/ResetPassword.jsx";
import { SessionInitializer, RequireSession, AuthProtected, RoleRedirect, OnboardingGuard } from "./components/SessionRoutes.jsx";
import Auth from "./pages/auth/Auth.jsx";
import BookingReview from "./pages/traveler/BookingReview/BookingReview.jsx";
import AirlineOnboardingWizard from "./pages/Onboarding/AirlineOnboardingWizard";

function App() {
  return (
    <ThemeProvider>
      <Router>
        <div className="min-h-screen bg-background transition-colors">
          <Toaster />
          <SessionInitializer />
          <Routes>
            {/* Authentication Routes - Protected for unauthenticated users only */}
            <Route
              path="/register"
              element={
                <AuthProtected>
                  <Auth isLogin={false} />
                </AuthProtected>
              }
            />
            <Route
              path="/login"
              element={
                <AuthProtected>
                  <Auth isLogin={true} />
                </AuthProtected>
              }
            />
            <Route
              path="/forgot-password"
              element={
                <AuthProtected>
                  <ForgotPassword />
                </AuthProtected>
              }
            />
            <Route
              path="/reset-password/:token"
              element={
                <AuthProtected>
                  <ResetPassword />
                </AuthProtected>
              }
            />
            <Route
              path="/reset-password"
              element={
                <AuthProtected>
                  <ResetPassword />
                </AuthProtected>
              }
            />

            {/* Landing Page - With role-based redirection for logged in users */}
            <Route
              path="/"
              element={
                <>
                  <RoleRedirect />
                  <LandingPage />
                </>
              }
            />
            {/* <Route path="/onboarding" element={<OnboardingPage />} /> */}
            <Route path="/airline-onboarding" element={<OnboardingGuard><AirlineOnboardingWizard /></OnboardingGuard>} />

            {/* Traveler Routes */}
            <Route
              path="/traveler"
              element={
                <div>
                  <Header />
                  <HomePage />
                </div>
              }
            />
            <Route
              path="/search"
              element={
                <div>
                  <Header />
                  <SearchResults />
                </div>
              }
            />
            <Route
              path="/search-results"
              element={
                <div>
                  <Header />
                  <SearchResults />
                </div>
              }
            />
            
            <Route element={<RequireSession />}>
            <Route
              path="/booking-review"
              element={
                <div>
                  <Header />
                  <BookingReview />
                </div>
              }
            />
            <Route
              path="/payment"
              element={
                <div>
                  <Header />
                  <PaymentPage />
                </div>
              }
            />
            <Route
              path="/bookings"
              element={
                <div>
                  <Header />
                  <BookingHistory />
                </div>
              }
            />
            <Route
              path="/booking-success/:bookingId"
              element={<BookingSuccess />}
            />
            <Route
              path="/payment-cancelled/:paymentId"
              element={<PaymentPage cancelled />}
            />
            <Route
              path="/view-ticket/:bookingId"
              element={
                <div>
                  <Header />
                  <Ticket />
                </div>
              }
            />
            <Route
              path="/ticket/:pnr"
              element={
                <div>
                  <Header />
                  <ETicket />
                </div>
              }
            />
            <Route
              path="/ticket"
              element={
                <div>
                  <Header />
                  <ETicket />
                </div>
              }
            />
            <Route
              path="/profile"
              element={
                <div>
                  <Header />
                  <UserProfile />
                </div>
              }
            />

            </Route>
            {/* Airline Dashboard Routes */}
            <Route element={<RequireSession roles={["ROLE_AIRLINE_OWNER"]} />}>
              <Route path="/airline/*" element={<AirlineDashboard />} />
            </Route>

            {/* Super Admin Dashboard Routes */}
            <Route element={<RequireSession roles={["ROLE_SYSTEM_ADMIN"]} />}>
              <Route path="/super-admin/*" element={<SuperAdminDashboard />} />
            </Route>
          </Routes>
        </div>
      </Router>
    </ThemeProvider>
  );
}

export default App;
