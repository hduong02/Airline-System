import React, { useState, useEffect } from "react";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ArrowRight, CreditCard, Plane } from "lucide-react";
import { cn } from "@/lib/utils";
import { useDispatch } from "react-redux";
import { getFlightFares } from "@/Redux/fare/fareThunk";
import { useNavigate, useSearchParams } from "react-router-dom";
import FareCard from "./FareCard";
import { getCabinClassesByAircraft } from "@/Redux/cabinClass/cabinClassThunk";
import { buildBookingPayload } from "@/services/Booking/bookingService";
import { getPassengerCount, isFareForSelection, selectInitialCabin } from "@/utils/bookingCheckout";

const FlightPricingModal = ({ isOpen, onClose, flight, onSelectFare }) => {
  const [selectedCabinId, setSelectedCabinId] = useState("");
  const [selectedFare, setSelectedFare] = useState(null);
  const [cabinResult, setCabinResult] = useState(null);
  const [fareResult, setFareResult] = useState(null);
  const dispatch = useDispatch();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const passengerCount = getPassengerCount(searchParams.get("numberOfTravellers"));
  const requestedCabin = searchParams.get("cabinClass") || flight?.cabinClass;
  const contextKey = `${flight?.id}:${flight?.flightId}:${flight?.aircraftId}`;
  const cabinClasses = cabinResult?.key === contextKey ? cabinResult.cabins : [];
  const selectedCabinClass = cabinClasses.find((cabin) => String(cabin.id) === selectedCabinId);
  const fareKey = `${contextKey}:${selectedCabinId}`;
  const faresReady = isOpen && !!selectedCabinClass && fareResult?.key === fareKey && !fareResult.loading;
  const fares = faresReady ? fareResult.fares : [];
  const canContinue = faresReady && !fareResult.error && fares.some((fare) => fare.id === selectedFare?.id) &&
    isFareForSelection(selectedFare, flight?.flightId, selectedCabinClass?.id);

  useEffect(() => {
    let active = true;
    setSelectedFare(null);
    setSelectedCabinId("");
    setCabinResult(null);
    setFareResult(null);
    if (!isOpen || !flight?.aircraftId) return;
    dispatch(getCabinClassesByAircraft(flight.aircraftId)).unwrap().then((cabins) => {
      if (!active) return;
      setCabinResult({ key: contextKey, cabins: cabins || [] });
      const initialCabin = selectInitialCabin(cabins || [], requestedCabin);
      setSelectedCabinId(initialCabin ? String(initialCabin.id) : "");
    }).catch((error) => {
      if (active) setCabinResult({ key: contextKey, cabins: [], error: String(error) });
    });
    return () => { active = false; };
  }, [isOpen, contextKey, flight?.aircraftId, requestedCabin, dispatch]);

  useEffect(() => {
    let active = true;
    setSelectedFare(null);
    if (!isOpen || !selectedCabinClass?.id || !flight?.flightId) return;
    setFareResult({ key: fareKey, fares: [], loading: true });
    dispatch(getFlightFares({ cabinId: selectedCabinClass.id, flightId: flight.flightId }))
      .unwrap().then((fares) => {
        if (active) setFareResult({ key: fareKey, fares: fares || [], loading: false });
      }).catch((error) => {
        if (active) setFareResult({ key: fareKey, fares: [], loading: false, error: String(error) });
      });
    return () => { active = false; };
  }, [isOpen, fareKey, selectedCabinClass?.id, flight?.flightId, dispatch]);

  const handleCabinChange = (id) => {
    setSelectedFare(null);
    setFareResult(null);
    setSelectedCabinId(id);
  };

  const handleContinueBooking = () => {
    if (!canContinue) return;

    try {
      const { bookingData, queryParams } = buildBookingPayload({
        flight,
        selectedFare,
        selectedCabinClass,
        numberOfTravellers: passengerCount,
      });

      // store data
      sessionStorage.setItem("bookingData", JSON.stringify(bookingData));

      // navigate
      const params = new URLSearchParams(queryParams);
      navigate(`/booking-review?${params.toString()}`);

      onSelectFare?.({ ...flight, selectedFare, selectedCabinClass });
      onClose?.();
    } catch (error) {
      console.error("Booking error:", error);
    }
  };

  return (
    <Dialog open={isOpen} onOpenChange={onClose}>
      <DialogContent className="max-w-5xl max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="text-2xl font-bold flex items-center gap-3">
            <div className="bg-gradient-to-r from-blue-600 to-purple-600 p-2 rounded-lg">
              <CreditCard className="h-6 w-6 text-white" />
            </div>
            Select Your Fare
          </DialogTitle>
          <DialogDescription className="text-base">
            Choose the fare that best suits your travel needs
          </DialogDescription>
        </DialogHeader>

        {/* Flight Summary Bar */}
        <div className="bg-gradient-to-r from-blue-50 to-purple-50 p-4 rounded-xl border border-blue-200 mb-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-4">
              <div className="text-center">
                <div className="text-2xl font-bold text-blue-600">
                  {flight?.departureAirportCode || "DEL"}
                </div>
                <div className="text-xs text-muted-foreground">
                  {new Date(flight?.departureTime).toLocaleTimeString("en-US", {
                    hour: "2-digit",
                    minute: "2-digit",
                  })}
                </div>
              </div>
              <div className="flex flex-col items-center">
                <Plane className="h-5 w-5 text-blue-600 mb-1" />
                <div className="text-xs text-muted-foreground">
                  {flight?.totalStops === 0
                    ? "Non-stop"
                    : `${flight?.totalStops} stop(s)`}
                </div>
              </div>
              <div className="text-center">
                <div className="text-2xl font-bold text-purple-600">
                  {flight?.arrivalAirportCode || "SGN"}
                </div>
                <div className="text-xs text-muted-foreground">
                  {new Date(flight?.arrivalTime).toLocaleTimeString("en-US", {
                    hour: "2-digit",
                    minute: "2-digit",
                  })}
                </div>
              </div>
            </div>
            <div className="text-right">
              <div className="text-xs text-muted-foreground mb-1">
                {flight?.airlineName}
              </div>
              <Badge variant="outline">{flight?.flightNumber}</Badge>
            </div>
          </div>
        </div>

        {/* Cabin Class Tabs */}
        <Tabs value={selectedCabinId} onValueChange={handleCabinChange}>
          <TabsList
            className="grid grid-cols-4 h-auto p-1 bg-muted/50
          w-full"
          >
            {cabinClasses.map((cabin) => {

              return (
                <TabsTrigger
                  key={cabin.id}
                  value={String(cabin.id)}
                  className="flex flex-col items-center gap-1 py-3 data-[state=active]:bg-white data-[state=active]:shadow-md"
                >
                  <div className="flex items-center gap-2">
                    <div
                      className={cn(
                        "p-2 rounded-lg bg-gradient-to-br",
                        cabin.color,
                      )}
                    >
                      {/* <Icon className="h-4 w-4 text-white" /> */}
                    </div>
                    <div>
                      <p className="text-xs font-semibold">{cabin.name}</p>
                    </div>
                  </div>
                </TabsTrigger>
              );
            })}
          </TabsList>

          {!cabinResult && <p role="status">Loading cabins...</p>}
          {cabinResult?.error && <p role="alert">{cabinResult.error}</p>}
          {cabinResult && !cabinResult.error && !cabinClasses.length && <p>No cabins available.</p>}
          {!!selectedCabinClass && !faresReady && <p role="status">Loading fares...</p>}
          {faresReady && fareResult.error && <p role="alert">{fareResult.error}</p>}
          {faresReady && !fareResult.error && !fares.length && <p>No fares available for this cabin.</p>}
          {/* Fare Cards Grid */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-x-6 gap-y-8">
            {fares.map((fare, index) => (
              <FareCard
                key={fare?.id || index}
                fare={fare}
                isSelected={selectedFare?.id === fare?.id}
                onSelect={() => setSelectedFare(fare)}
                passengerCount={passengerCount}
                flightType={flight?.flightType}
              />
            ))}
          </div>
        </Tabs>

        {/* Action Buttons */}
        <div className="flex items-center justify-between gap-4 mt-6 pt-6 border-t">
          <Button variant="outline" onClick={onClose} className="px-8">
            Cancel
          </Button>
          <Button
            onClick={handleContinueBooking}
            disabled={!canContinue}
            className="px-8 bg-gradient-to-r from-blue-600 to-purple-600 hover:from-blue-700 hover:to-purple-700"
          >
            Continue to Booking
            <ArrowRight className="h-4 w-4 ml-2" />
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default FlightPricingModal;
