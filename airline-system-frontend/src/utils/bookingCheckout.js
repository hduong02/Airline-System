export const CABIN_CLASSES = ['ECONOMY', 'PREMIUM_ECONOMY', 'BUSINESS', 'FIRST'];

export const getPassengerCount = (value) => {
  const count = Number(value);
  return Number.isInteger(count) && count > 0 ? Math.min(count, 9) : 1;
};

const amount = (value) => {
  const number = Number(value);
  return Number.isFinite(number) ? number : 0;
};

export const getSeatPrice = (seat) => amount(seat?.price);

export const calculateBookingTotals = ({
  fare,
  passengerCount = 1,
  selectedSeats = [],
  selectedMeals = [],
  selectedBaggage = [],
  travelProtection = null,
} = {}) => {
  const count = getPassengerCount(passengerCount);
  const baseFare = amount(fare?.baseFare) * count;
  const taxes = amount(fare?.taxesAndFees) * count;
  const airlineFees = amount(fare?.airlineFees) * count;
  const subtotal = baseFare + taxes + airlineFees;
  const seatCharges = selectedSeats.reduce((sum, seat) => sum + getSeatPrice(seat), 0);
  const mealCharges = selectedMeals.reduce((sum, meal) => sum + amount(meal?.price), 0);
  const baggageCharges = selectedBaggage.reduce(
    (sum, bag) => sum + amount(bag?.price) * amount(bag?.quantity), 0,
  );
  const travelProtectionCharge = amount(travelProtection?.price);
  const addOnsTotal = seatCharges + mealCharges + baggageCharges + travelProtectionCharge;
  return {
    baseFare, taxes, airlineFees, subtotal, seatCharges, mealCharges,
    baggageCharges, travelProtectionCharge, addOnsTotal, grandTotal: subtotal + addOnsTotal,
  };
};

export const normalizeContactInfo = (contact = {}) => {
  const phone = String(contact.phone || '').trim().replace(/[\s()-]/g, '');
  return {
    email: String(contact.email || '').trim(),
    phone: phone.startsWith('+') ? phone : `${contact.countryCode || ''}${phone}`,
  };
};

export const validateTravellerData = (data, passengerCount) => {
  if (!Array.isArray(data?.passengers) || data.passengers.length !== passengerCount ||
    !data.passengers.every((passenger) =>
      ['title', 'firstName', 'lastName', 'gender'].every((field) =>
        typeof passenger?.[field] === 'string' && passenger[field].trim(),
      ) && ['MALE', 'FEMALE', 'OTHER'].includes(passenger.gender.trim().toUpperCase()),
    )) {
    return 'Please fill all required traveller details';
  }
  const contact = normalizeContactInfo(data.contactInfo);
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(contact.email)) {
    return 'Please provide a valid contact email address';
  }
  if (!/^\+?\d{10,15}$/.test(contact.phone) || !String(data.contactInfo?.phone || '').trim()) {
    return 'Please provide a valid contact phone number (10 to 15 digits including country code)';
  }
  return null;
};

const cabinType = (value) => {
  const code = String(value || '').toUpperCase().replace(/[ -]+/g, '_').replace(/_CLASS$/, '');
  return ({ E: 'ECONOMY', Y: 'ECONOMY', W: 'PREMIUM_ECONOMY',
    J: 'BUSINESS', C: 'BUSINESS', F: 'FIRST' })[code] || code;
};

export const selectInitialCabin = (cabins, requested) => {
  const value = requested?.id ?? requested?.code ?? requested?.name ?? requested;
  return cabins.find((cabin) => String(cabin.id) === String(value) ||
    cabinType(cabin.code) === cabinType(value) || cabinType(cabin.name) === cabinType(value)) ||
    cabins[0] || null;
};

export const isFareForSelection = (fare, flightId, cabinId) => Boolean(
  fare && CABIN_CLASSES.includes(fare.cabinClass) &&
  String(fare.cabinClassId) === String(cabinId) &&
  String(fare.flightId) === String(flightId),
);
