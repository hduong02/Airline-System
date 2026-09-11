import "./App.css";
import { lazy, Suspense } from "react";
import {
  BrowserRouter as Router,
  Routes,
  Route,
} from "react-router-dom";
import { Toaster } from "./components/ui/sonner.jsx";
import Header from "./pages/traveler/Home/Header.jsx";
import { SessionInitializer, RequireSession, AuthProtected, RoleRedirect, OnboardingGuard } from "./components/SessionRoutes.jsx";

// Load route content on demand so registration does not load dashboards or PDFs.
const LandingPage = lazy(() => import("./pages/Landing/LandingPage.jsx"));
const HomePage = lazy(() => import("./pages/traveler/Home/HomePage.jsx"));
const SearchResults = lazy(() => import("./pages/traveler/FlightList/SearchResults.jsx"));
const PaymentPage = lazy(() => import("./pages/traveler/Payment/PaymentPage.jsx"));
const BookingHistory = lazy(() => import("./pages/traveler/BookingHistory/BookingHistory.jsx"));
const BookingSuccess = lazy(() => import("./pages/traveler/BookingSuccess/BookingSuccess.jsx"));
const ETicket = lazy(() => import("./pages/traveler/Ticket/ETicket.jsx"));
const Ticket = lazy(() => import("./pages/traveler/Ticket/Ticket.jsx"));
const UserProfile = lazy(() => import("./pages/traveler/Profile/UserProfile.jsx"));
const AirlineDashboard = lazy(() => import("./pages/airline/Dashboard/AirlineDashboard.jsx"));
const SuperAdminDashboard = lazy(() => import("./pages/super-admin/Dashboard/SuperAdminDashboard.jsx"));
const ForgotPassword = lazy(() => import("./pages/auth/ForgotPassword.jsx"));
const ResetPassword = lazy(() => import("./pages/auth/ResetPassword.jsx"));
const Auth = lazy(() => import("./pages/auth/Auth.jsx"));
const BookingReview = lazy(() => import("./pages/traveler/BookingReview/BookingReview.jsx"));
const AirlineOnboardingWizard = lazy(() => import("./pages/Onboarding/AirlineOnboardingWizard.jsx"));

function App() {
  return (
      <Router>
        <div className="min-h-screen bg-background transition-colors">
          <Toaster />
          <SessionInitializer />
          <Suspense fallback={<div role="status" className="p-8 text-center">Loading page…</div>}>
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
          </Suspense>
        </div>
      </Router>
  );
}

export default App;
