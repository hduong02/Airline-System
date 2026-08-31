import React, { useState, useEffect, useRef } from "react";
import { motion as Motion, AnimatePresence } from "framer-motion";
import { ArrowLeft, CreditCard, X, AlertCircle } from "lucide-react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useDispatch, useSelector } from "react-redux";
import { toast } from "sonner";

// Import components
import FlightDetailsOverview from "./FlightDetailsOverview";
import TravellerDetailsForm from "./TravellerDetailsForm";
import SeatSelection from "./SeatSelection";
import MealSelection from "./MealSelection";
import BaggageSelection from "./BaggageSelection";
import CancellationAndDateChangePolicy from "./CancellationAndDateChangePolicy";

import TripSecure from "./TripSecure";
import ImportantInformation from "./ImportantInformation";
import FareSummaryCard from "./FareSummaryCard";

// Import Redux thunks
import {
  getAllFlightCabinAncillariesByType,
  getFlightCabinAncillariesByType,
} from "@/Redux/flightCabinAncillary/flightCabinAncillaryThunk";
import { getFlightInstanceById } from "@/Redux/flightInstance/flightInstanceThunk";
import { createBooking } from "@/Redux/booking/bookingThunk";

import { getFareRuleByFare } from "@/Redux/fareRules/fareRulesThunk";
import { fetchFlightMealsByFlightId } from "@/Redux/flightMeal/flightMealThunk";
import { getFlightInstanceCabinsByFlightInstanceAndCabinClass } from "@/Redux/flightInstanceCabin/flightInstanceCabinThunk";
import { getBaggagePolicyByFare } from "@/Redux/baggagePolicy/baggagePolicyThunk";
import { getFareById } from "@/Redux/fare/fareThunk";
import {
  CABIN_CLASSES, calculateBookingTotals, getPassengerCount,
  normalizeContactInfo, validateTravellerData,
} from "@/utils/bookingCheckout";

const BookingReview = () => {
  const navigate = useNavigate();
  const dispatch = useDispatch();
  const [searchParams] = useSearchParams();

  const [selectedFare, setSelectedFare] = useState(null);
  const [fareError, setFareError] = useState(null);
  const submittingRef = useRef(false);
  const passengerCount = getPassengerCount(searchParams.get("numberOfTravellers"));
  const flightId = searchParams.get("flightId");
  const fareId = searchParams.get("fareId");
  const flightInstanceId = searchParams.get("flightInstanceId");
  const { flightInstance } = useSelector((store) => store.flightInstance);

  // Redux state
  const { ancillariesByType } = useSelector(
    (state) => state.flightCabinAncillary,
  );

  const { loading: bookingLoading, error: bookingError } = useSelector(
    (state) => state.booking,
  );

  // State management
  const [travellerData, setTravellerData] = useState({ passengers: [], contactInfo: { email: "", phone: "", countryCode: "+91" } });
  const [selectedSeats, setSelectedSeats] = useState([]); // Changed to array for multiple passengers
  const [selectedMeals, setSelectedMeals] = useState([]);
  const [selectedBaggage, setSelectedBaggage] = useState([]);

  const [selectedTravelProtection, setSelectedTravelProtection] = useState(null);

  const [loading, setLoading] = useState(true);
  const handleSeatSelection = (passengerIndex, seat) => {
    setSelectedSeats((seats) => {
      const updatedSeats = [...seats];
      updatedSeats[passengerIndex] = seat;
      return updatedSeats;
    });
  };

  useEffect(() => {
    let active = true;
    setSelectedFare(null);
    setFareError(null);
    setLoading(true);
    if (!fareId) {
      setFareError("Please select a flight fare before booking.");
      setLoading(false);
      return;
    }
    dispatch(getBaggagePolicyByFare(fareId));
    dispatch(getFareRuleByFare(fareId));
    dispatch(getFareById(fareId)).unwrap().then((fare) => {
      if (active) setSelectedFare(fare);
    }).catch((error) => {
      if (active) setFareError(String(error || "Unable to load this fare."));
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [fareId, dispatch]);
  // Fetch flight instance data when flightInstanceId is available
  useEffect(() => {
    if (flightInstanceId) {
      console.log("Fetching flight instance:", flightInstanceId);
      dispatch(getFlightInstanceById(flightInstanceId));

    }
  }, [flightInstanceId, dispatch]);

  useEffect(() => {
    if (flightInstanceId && selectedFare?.cabinClassId) {
      dispatch(getFlightInstanceCabinsByFlightInstanceAndCabinClass({
        flightInstanceId, cabinClassId: selectedFare.cabinClassId,
      }));
    }
  }, [flightInstanceId, selectedFare?.cabinClassId, dispatch]);

  // Fetch ancillaries by type when flightId and cabinClassId are available
  useEffect(() => {
    if (flightId) {
      // fetch meals by flightId (not cabin-specific)
      dispatch(fetchFlightMealsByFlightId(flightId));
    }

    if (flightId && selectedFare?.cabinClassId) {
      const cabinClassId = selectedFare?.cabinClassId;


      // Fetch Travel Protection (flexibility, cancellation protection)
      dispatch(
        getFlightCabinAncillariesByType({
          flightId,
          cabinClassId,
          type: "TRAVEL_PROTECTION",
        }),
      );

      // Fetch Baggage
      dispatch(
        getAllFlightCabinAncillariesByType({
          flightId,
          cabinClassId,
          type: "BAGGAGE",
        }),
      );
    }
  }, [flightId, selectedFare, dispatch]);

  const travelProtectionData = selectedTravelProtection
    ? ancillariesByType?.TRAVEL_PROTECTION : null;
  const totals = calculateBookingTotals({
    fare: selectedFare, passengerCount, selectedSeats, selectedMeals,
    selectedBaggage, travelProtection: travelProtectionData,
  });
  const { grandTotal } = totals;
  const checkoutLoading = loading || bookingLoading;

  const handleProceedToPayment = async () => {
    if (submittingRef.current || checkoutLoading) return;
    const validationError = validateTravellerData(travellerData, passengerCount);
    if (validationError) {
      toast.error(validationError);
      return;
    }
    if (!selectedFare || !CABIN_CLASSES.includes(selectedFare.cabinClass) ||
      String(selectedFare.id) !== String(fareId) ||
      String(selectedFare.flightId) !== String(flightId) || !flightInstanceId) {
      toast.error("Please select a valid flight and fare before booking.");
      return;
    }
    // Collect all ancillary IDs
    const ancillaryIds = [];

    // Add seat ancillary IDs for all passengers
    // selectedSeats.forEach((seat) => {
    //   if (seat?.id) {
    //     ancillaryIds.push(seat.id);
    //   }
    // });

    const mealIds = [];

    // Add meal ancillary IDs
    selectedMeals.forEach((meal) => {
      if (meal.flightMealId) mealIds.push(meal.flightMealId);
    });

    // Add baggage ancillary IDs
    selectedBaggage.forEach((bag) => {
      if (bag.id) {
        // Add multiple times based on quantity
        for (let i = 0; i < bag.quantity; i++) {
          ancillaryIds.push(bag.id);
        }
      }
    });

    // Add travel protection ancillary ID
    if (selectedTravelProtection && travelProtectionData?.id) {
      ancillaryIds.push(travelProtectionData.id);
    }




    // Get seat numbers array for all passengers
    const seatNumbers = selectedSeats
      .filter((seat) => seat !== null && seat !== undefined)
      .map((seat) => seat.seatNumber);

    // Get dietary preferences from selected meals
    const getDietaryPreference = (passengerIndex) => {
      const passengerMeal = selectedMeals[passengerIndex];
      if (passengerMeal?.dietaryRestriction) {
        const restrictions = {
          VEGETARIAN: "Vegetarian",
          VEGAN: "Vegan",
          HALAL: "Halal",
          KOSHER: "Kosher",
          GLUTEN_FREE: "Gluten Free",
        };
        return restrictions[passengerMeal.dietaryRestriction] || null;
      }
      return null;
    };

    // Backend API format booking data
    const bookingDataForAPI = {
      flightId: parseInt(flightId) || null,
      flightInstanceId: parseInt(flightInstanceId) || null,
      cabinClass: selectedFare.cabinClass,
      tripType: searchParams.get("tripType") || "ONE_WAY",
      fareId: parseInt(fareId) || null,
      passengers: travellerData.passengers.map((t, index) => ({
        firstName: t.firstName.trim(),
        lastName: t.lastName.trim(),
        email: t.email || "",
        phone: t.phone ? `${t.countryCode || "+91"}${t.phone}` : "",
        dateOfBirth: t.dob || null,
        gender: t.gender ? t.gender.trim().toUpperCase() : null,
        seatNumber: selectedSeats[index]
          ? selectedSeats[index].seatNumber
          : null,
        seatInstanceId: selectedSeats[index] ? selectedSeats[index].id : null,
        passportNumber: t.passportNumber || null,
        nationality: t.nationality || "IN",
        frequentFlyerNumber: t.frequentFlyerNumber || null,
        requiresWheelchairAssistance: t.requiresWheelchairAssistance || false,
        dietaryPreferences: getDietaryPreference(index),
        medicalConditions: t.medicalConditions || null,
      })),
      contactInfo: normalizeContactInfo(travellerData.contactInfo),
      ancillaryIds: ancillaryIds,
      mealIds: mealIds,
      promoCode: searchParams.get("promoCode") || null,
      seatNumbers: seatNumbers,
    };

    // Call the booking API
    submittingRef.current = true;
    try {
      toast.loading("Creating your booking...", { id: "booking-toast" });

      const result = await dispatch(createBooking(bookingDataForAPI)).unwrap();

      console.log("✅ Booking created successfully:", result);

      // Check for payment redirect URL
      const checkoutUrl = result.checkoutUrl || result.payment_link_url;

      if (checkoutUrl && result.success) {
        // Show redirecting message
        toast.success("Booking created! Redirecting to payment gateway...", {
          id: "booking-toast",
          duration: 3000,
        });
        // The thunk will handle the redirect
      } else if (result.success) {
        // No payment needed, booking confirmed
        toast.success(
          `Booking confirmed! Total:$${grandTotal.toLocaleString()}\nBooking Reference: ${result.bookingReference || "N/A"
          }`,
          { id: "booking-toast", duration: 5000 },
        );
      } else {
        toast.error(result.message || "Booking failed. Please try again.", {
          id: "booking-toast",
        });
      }
    } catch (error) {
      console.error("❌ Booking failed:", error);
      toast.error(`Booking failed: ${error || "Please try again"}`, {
        id: "booking-toast",
      });
    } finally {
      submittingRef.current = false;
    }
  };

  // Show loading state
  if (loading) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <div className="text-center">
          <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600 mx-auto mb-4"></div>
          <p className="text-gray-600">Loading booking details...</p>
        </div>
      </div>
    );
  }

  // Use real booking data if available, otherwise use mock data

  return (
    <div className="min-h-screen bg-gray-50">
      {fareError && <p role="alert" className="p-4 text-red-700">{fareError}</p>}
      {/* Error Notification */}
      <AnimatePresence>
        {bookingError && (
          <Motion.div
            initial={{ opacity: 0, y: -50 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -50 }}
            className="fixed top-4 right-4 z-50 max-w-md"
          >
            <div className="bg-red-50 border border-red-200 rounded-lg p-4 shadow-lg">
              <div className="flex items-start gap-3">
                <AlertCircle className="w-5 h-5 text-red-600 flex-shrink-0 mt-0.5" />
                <div className="flex-1">
                  <h3 className="text-sm font-semibold text-red-900 mb-1">
                    Booking Failed
                  </h3>
                  <p className="text-sm text-red-700">{bookingError}</p>
                </div>
                <button
                  onClick={() =>
                    dispatch({ type: "booking/clearBookingError" })
                  }
                  className="text-red-400 hover:text-red-600 transition-colors"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>
            </div>
          </Motion.div>
        )}
      </AnimatePresence>

      {/* Header */}
      <div className="bg-white border-b border-gray-200 sticky top-0 z-40 shadow-sm">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4">
          <div className="flex items-center justify-between">
            <button
              onClick={() => navigate(-1)}
              className="flex items-center gap-2 text-gray-600 hover:text-gray-800 transition-colors"
            >
              <ArrowLeft className="w-5 h-5" />
              <span className="text-sm font-medium">Back to Flight Search</span>
            </button>
            <div className="hidden md:block">
              <p className="text-sm text-gray-600">
                Need help? Call:{" "}
                <span className="font-semibold text-blue-600">
                  1800-123-4567
                </span>
              </p>
            </div>
          </div>
        </div>
      </div>

      {/* Main Content */}
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
        <Motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ duration: 0.5 }}
        >
          {/* Page Title */}
          <div className="mb-6">
            <h1 className="text-2xl md:text-3xl font-bold text-gray-900 mb-2">
              Complete Your Booking
            </h1>
            <p className="text-gray-600 text-sm md:text-base">
              Review your flight details and fill in traveller information
            </p>
          </div>

          {/* Two Column Layout */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
            {/* Left Column - Main Content */}
            <div className="col-span-1 lg:col-span-2 space-y-6">
              {/* 1. Flight Details Summary */}
              <FlightDetailsOverview flightData={flightInstance} />

              {/* Travel Insurance - Using real API data */}
              <TripSecure
                selectedTravelProtection={selectedTravelProtection}
                onSelectTravelProtection={setSelectedTravelProtection}
              />

              {/* 5. Cancellation & Date Change Policy - Using real API data */}
              <CancellationAndDateChangePolicy />

              {/* 2. Traveller Details Form */}
              <TravellerDetailsForm
                key={passengerCount}
                passengerCount={passengerCount}
                onTravellerDataChange={setTravellerData}
              />

              {/* 3. Add-ons Section */}
              <div className="space-y-6">
                {/* Seat Selection */}
                {/* Now using Redux data from flightInstance.seats and flightInstance.seatMap */}
                <SeatSelection
                  selectedSeats={selectedSeats}
                  onSelectSeat={handleSeatSelection}
                  passengerCount={passengerCount}
                />

                {/* Meal Selection */}
                {/* Note: Meals are managed via separate Meal entity, not ancillaries */}
                {/* Now using Redux data from flightMeal store */}
                <MealSelection
                  selectedMeals={selectedMeals}
                  onSelectMeal={setSelectedMeals}
                />

                {/* Baggage Selection */}
                {/* Using Redux data from ancillariesByType.BAGGAGE */}
                <BaggageSelection
                  selectedBaggage={selectedBaggage}
                  onSelectBaggage={setSelectedBaggage}
                />

                {/* 6. Important Information */}
                <ImportantInformation />
              </div>
            </div>

            {/* Right Column - Fare Summary (Sticky) */}
            <div className="col-span-1">
              <div className="sticky top-24">
                <FareSummaryCard
                  totals={totals}
                  selectedSeats={selectedSeats}
                  selectedMeals={selectedMeals}
                  selectedBaggage={selectedBaggage}
                  travelProtection={travelProtectionData}
                  onProceedToPayment={handleProceedToPayment}
                  isLoading={checkoutLoading}
                  totalPassengers={passengerCount}
                />
              </div>
            </div>
          </div>

          {/* Mobile Sticky Bottom Bar */}
          <div className="lg:hidden fixed bottom-0 left-0 right-0 bg-white border-t border-gray-200 p-4 shadow-lg z-30">
            <div className="flex items-center justify-between gap-4">
              <div>
                <p className="text-xs text-gray-600">Total Amount</p>
                <p className="text-lg font-bold text-gray-900">
                  ${grandTotal.toLocaleString()}
                </p>
              </div>
              <button
                onClick={handleProceedToPayment}
                disabled={checkoutLoading}
                className="flex-1 max-w-xs py-3 px-6 bg-gradient-to-r from-blue-600 to-indigo-600 text-white font-semibold rounded-xl hover:from-blue-700 hover:to-indigo-700 transition-all shadow-lg flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {checkoutLoading ? (
                  <>
                    <div className="animate-spin rounded-full h-5 w-5 border-b-2 border-white"></div>
                    Processing...
                  </>
                ) : (
                  <>
                    <CreditCard className="w-5 h-5" />
                    Continue
                  </>
                )}
              </button>
            </div>
          </div>

          {/* Add padding at bottom for mobile sticky bar */}
          <div className="lg:hidden h-24"></div>
        </Motion.div>
      </div>
    </div>
  );
};

export default BookingReview;
