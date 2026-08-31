// services/bookingService.js

import {
  encodeBase64, generateItineraryId,
  generateCrId,
  generateRKey,
  buildPaxString
} from "@/utils/bookingUtils";
import { getPassengerCount, isFareForSelection } from "@/utils/bookingCheckout";


export const buildBookingPayload = ({
  flight,
  selectedFare,
  selectedCabinClass,
  numberOfTravellers,
}) => {
  if (!flight || !selectedFare || !selectedCabinClass) {
    throw new Error("Invalid booking data");
  }
  if (!isFareForSelection(selectedFare, flight.flightId, selectedCabinClass.id)) {
    throw new Error("The selected fare does not belong to this flight and cabin");
  }
  numberOfTravellers = getPassengerCount(numberOfTravellers);
  const cabinClass = selectedFare.cabinClass;

  const itineraryId = generateItineraryId(flight);
  const crId = generateCrId();
  const rKey = generateRKey();
  const paxString = buildPaxString(numberOfTravellers);

  const searchFilter = {
    c: cabinClass.charAt(0),
    p: paxString,
    s: `${flight.departureAirportCode}-${flight.arrivalAirportCode}-${flight.departureTime}`,
    ItineraryId: itineraryId,
    PaxType: paxString,
    Intl: false,
    CabinClass: cabinClass,
    Ccde: "IN",
    ForwardFlowRequired: true,
    flightInstanceId: flight.id,
    flightId: flight.flightId
  };

  const xflt = encodeBase64(searchFilter);

  const bookingData = {
    flight: { ...flight, selectedCabinClass },
    fare: selectedFare,
    flightType: flight.flightType,
  };

  return {
    bookingData,
    queryParams: {
      itineraryId,
      cur: "USD",
      ccde: "IN",
      crId,
      rKey: encodeURIComponent(rKey),
      userCurrency: "USD",
      xflt,
      numberOfTravellers: String(numberOfTravellers),
      cabinClass,
      flightInstanceId: flight.id,
      flightId: flight.flightId,
      fareId: selectedFare.id,
    },
  };
};
