export const mockFlightData = {
  departure: {
    airport: "Noi Bai International Airport",
    city: "Hanoi",
    code: "HAN",
    terminal: "Terminal 2",
    time: "06:15",
    date: "Mon, 15 Jan"
  },
  arrival: {
    airport: "Tan Son Nhat International Airport",
    city: "Ho Chi Minh City",
    code: "SGN",
    terminal: "Terminal 1",
    time: "08:20",
    date: "Mon, 15 Jan"
  },
  airline: {
    name: "Vietnam Airlines",
    code: "VN",
    flightNumber: "VN 213",
    logo: "https://images.makemytrip.com/apac/flights/airlines/logos/VN.png"
  },
  duration: "2h 5m",
  stops: 0,
  aircraft: "Airbus A321neo",
  cabinClass: "Economy",
  baggage: {
    cabin: "7 Kgs (1 piece only) / Adult",
    checkIn: "23 Kgs (1 piece only) / Adult"
  },
  passengers: [
    {
      id: 1,
      type: "Adult",
      firstName: "Minh",
      lastName: "Nguyen",
      gender: "Male",
      age: 32
    }
  ]
};

export const mockFareData = {
  baseFare: 1250000,
  taxes: 320000,
  seatCharges: 0,
  mealCharges: 0,
  flexibilityAddOn: 0,
  tripSecure: 0,
  currency: "VND",
  totalPassengers: 1
};

export const mockPolicyData = {
  cancellation: {
    title: "Cancellation Policy",
    rules: [
      {
        timeframe: "0-2 hours before departure",
        penalty: "Non-refundable"
      },
      {
        timeframe: "2-24 hours before departure",
        penalty: "₫700,000 + ₫100,000 airline fee per passenger"
      },
      {
        timeframe: "24+ hours before departure",
        penalty: "₫500,000 + ₫100,000 airline fee per passenger"
      }
    ]
  },
  dateChange: {
    title: "Date Change Policy",
    rules: [
      {
        timeframe: "0-2 hours before departure",
        penalty: "₫600,000 + Fare difference"
      },
      {
        timeframe: "2-24 hours before departure",
        penalty: "₫500,000 + Fare difference"
      },
      {
        timeframe: "24+ hours before departure",
        penalty: "₫400,000 + Fare difference"
      }
    ]
  }
};

export const mockSeatsData = [
  { id: "1A", type: "window", price: 80000, available: true, row: 1, column: "A" },
  { id: "1B", type: "middle", price: 40000, available: true, row: 1, column: "B" },
  { id: "1C", type: "aisle", price: 60000, available: true, row: 1, column: "C" },
  { id: "2A", type: "window", price: 80000, available: false, row: 2, column: "A" },
  { id: "2B", type: "middle", price: 40000, available: true, row: 2, column: "B" },
  { id: "2C", type: "aisle", price: 60000, available: true, row: 2, column: "C" },
  { id: "3A", type: "window", price: 60000, available: true, row: 3, column: "A" },
  { id: "3B", type: "middle", price: 30000, available: true, row: 3, column: "B" },
  { id: "3C", type: "aisle", price: 50000, available: true, row: 3, column: "C" },
  { id: "12A", type: "window-extra", price: 120000, available: true, row: 12, column: "A", isExtraLegroom: true },
  { id: "12B", type: "middle-extra", price: 100000, available: true, row: 12, column: "B", isExtraLegroom: true },
  { id: "12C", type: "aisle-extra", price: 120000, available: true, row: 12, column: "C", isExtraLegroom: true }
];

export const mockMealsData = [
  {
    id: "veg-1",
    name: "Com Chay (Vegetarian Rice)",
    description: "Steamed rice with stir-fried vegetables and tofu",
    price: 60000,
    category: "Vegetarian",
    image: "🍚",
    available: true
  },
  {
    id: "nonveg-1",
    name: "Pho Ga (Chicken Pho)",
    description: "Traditional Vietnamese chicken noodle soup",
    price: 85000,
    category: "Non-Vegetarian",
    image: "🍜",
    available: true
  },
  {
    id: "veg-2",
    name: "Banh Mi Chay",
    description: "Vegetarian Vietnamese baguette with pickled vegetables",
    price: 45000,
    category: "Vegetarian",
    image: "🥖",
    available: true
  },
  {
    id: "snack-1",
    name: "Assorted Snack Box",
    description: "Mix of rice crackers, dried fruit, and nuts",
    price: 35000,
    category: "Snacks",
    image: "🍿",
    available: true
  }
];

export const flexibilityAddOnData = {
  title: "Unsure of your travel plans? Get full flexibility with our special add-ons.",
  options: [
    {
      id: "flex-basic",
      name: "Flex Basic",
      price: 70000,
      features: [
        "Free date change (once)",
        "Change fee waiver",
        "Fare difference applicable",
        "Valid for 24 hours before departure"
      ],
      popular: false
    },
    {
      id: "flex-plus",
      name: "Flex Plus",
      price: 140000,
      features: [
        "Free date change (twice)",
        "Change fee waiver",
        "Partial cancellation refund (50%)",
        "Valid till departure"
      ],
      popular: true
    }
  ]
};

export const tripSecureData = {
  title: "Protect your trip with TripSecure",
  description: "Comprehensive travel insurance covering medical emergencies, baggage loss, and trip cancellations.",
  options: [
    {
      id: "basic",
      name: "Basic Coverage",
      price: 35000,
      features: [
        "Medical coverage up to ₫12,000,000",
        "Baggage loss up to ₫2,500,000",
        "Trip delay compensation",
        "24/7 assistance"
      ]
    },
    {
      id: "premium",
      name: "Premium Coverage",
      price: 70000,
      features: [
        "Medical coverage up to ₫50,000,000",
        "Baggage loss up to ₫6,000,000",
        "Trip cancellation refund (75%)",
        "Flight delay compensation",
        "24/7 priority assistance"
      ],
      recommended: true
    }
  ]
};