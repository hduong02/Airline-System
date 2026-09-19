import { formatDateOnly } from "@/utils/dateOnly";
import * as React from "react"
import { Calendar as CalendarIcon, Users, Search, ArrowLeftRight, Plane, MapPin, CalendarDays, GraduationCap, Shield, User, Stethoscope, Check } from "lucide-react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog"
import { Calendar } from "@/components/ui/calendar"
import { useSelector, useDispatch } from "react-redux"
import { listAllAirports } from "@/Redux/airport/airportThunk"
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group"
import { Label } from "@/components/ui/label"
import AirportPicker from "./AirportPicker"

const FlightSearchBar = ({ onSearch, className, destinationPrefill, initialSearchData }) => {
  const dispatch = useDispatch()
  const { airports, loading: airportsLoading, error: airportsError } = useSelector((state) => state.airport)

  const [tripType, setTripType] = React.useState("oneWay") // roundTrip, oneWay, multiCity
  const [specialFare, setSpecialFare] = React.useState("regular")
  const [searchData, setSearchData] = React.useState({
    departureAirportId: initialSearchData?.departureAirportId || "",
    arrivalAirportId: initialSearchData?.arrivalAirportId || "",
    departureDate: initialSearchData?.departureDate || new Date(), // Default to today
    returnDate: null,
    numberOfTravellers: initialSearchData?.passengers || 1,
    cabinClass: initialSearchData?.cabinClass || "ECONOMY"
  })

  const [departureDateOpen, setDepartureDateOpen] = React.useState(false)
  const [searchError, setSearchError] = React.useState("")
  const [passengersOpen, setPassengersOpen] = React.useState(false)
  const airportsTouched = React.useRef(false)
  const defaultsSet = React.useRef(false)
  const appliedPrefill = React.useRef(null)
  const [airportsLoaded, setAirportsLoaded] = React.useState(airports.length > 0)

  const cabinClasses = [
    { value: "ECONOMY", label: "Economy" },
    { value: "PREMIUM_ECONOMY", label: "Premium Economy" },
    { value: "BUSINESS", label: "Business" },
    { value: "FIRST", label: "First Class" }
  ]

  const specialFares = [
    {
      id: "regular",
      label: "Regular",
      description: "Regular fares",
      icon: User,
      discount: null
    },
    {
      id: "student",
      label: "Student",
      description: "Extra discounts/baggage",
      icon: GraduationCap,
      discount: null
    },
    {
      id: "armed_forces",
      label: "Armed Forces",
      description: "Up to$600 off",
      icon: Shield,
      discount: "$600"
    },
    {
      id: "senior_citizen",
      label: "Senior Citizen",
      description: "Up to$600 off",
      icon: User,
      discount: "$600"
    },
    {
      id: "doctor_nurses",
      label: "Doctor and Nurses",
      description: "Up to$600 off",
      icon: Stethoscope,
      discount: "$600"
    }
  ]

  // Load airports on component mount
  React.useEffect(() => {
    if (airports.length || airportsLoading) return
    let mounted = true
    Promise.resolve(dispatch(listAllAirports())).then(() => {
      if (mounted) setAirportsLoaded(true)
    })
    return () => { mounted = false }
  }, [dispatch])

  // Defaults fill only untouched fields; a queued destination always takes precedence.
  React.useEffect(() => {
    if (!airports.length || airportsLoading || airportsTouched.current || defaultsSet.current) return
    defaultsSet.current = true
    const from = airports.find(airport => airport.iataCode === "HAN")
    const to = airports.find(airport => airport.iataCode === "SGN")
    setSearchData(prev => ({
      ...prev,
      departureAirportId: prev.departureAirportId || (from ? Number(from.id) : ""),
      arrivalAirportId: prev.arrivalAirportId || (!destinationPrefill && to ? Number(to.id) : "")
    }))
  }, [airports, airportsLoading, destinationPrefill])

  React.useEffect(() => {
    if (!destinationPrefill || airportsLoading || appliedPrefill.current === destinationPrefill.clickId) return
    // Wait for the initial airport request before declaring a destination unavailable.
    if (!airports.length && !airportsError && !airportsLoaded) return
    appliedPrefill.current = destinationPrefill.clickId
    const destination = airports.find(airport => airport.iataCode?.toUpperCase() === destinationPrefill.iataCode?.toUpperCase())
    if (!destination) {
      setSearchError("This destination is currently unavailable. Please choose another airport.")
      return
    }
    const id = Number(destination.id)
    const originId = searchData.departureAirportId || (!airportsTouched.current && airports.find(airport => airport.iataCode === "HAN")?.id)
    const sameOrigin = Number(originId) === id
    airportsTouched.current = true
    setSearchData(prev => ({ ...prev, arrivalAirportId: id, departureAirportId: Number(prev.departureAirportId) === id ? "" : prev.departureAirportId }))
    setSearchError(sameOrigin ? "Choose a different departure airport for this destination." : "")
  }, [destinationPrefill, airports, airportsLoading, airportsError, airportsLoaded, searchData.departureAirportId])

  const selectAirport = (field, id) => {
    airportsTouched.current = true
    setSearchData(prev => ({ ...prev, [field]: id }))
    setSearchError("")
  }

  const handleSearch = () => {
    const departureId = Number(searchData.departureAirportId)
    const arrivalId = Number(searchData.arrivalAirportId)
    if (!Number.isSafeInteger(departureId) || departureId <= 0 || !Number.isSafeInteger(arrivalId) || arrivalId <= 0 ||
        !airports.some(airport => Number(airport.id) === departureId) || !airports.some(airport => Number(airport.id) === arrivalId) ||
        !formatDateOnly(searchData.departureDate)) {
      setSearchError("Choose valid departure and arrival airports and a departure date.")
      return
    }
    if (departureId === arrivalId) {
      setSearchError("Choose different departure and arrival airports")
      return
    }
    setSearchError("");
    if (searchData.departureAirportId && searchData.arrivalAirportId && searchData.departureDate) {
      // Format date as YYYY-MM-DD for API
      const formattedDate = formatDateOnly(searchData.departureDate)

      const searchParams = {
        departureAirportId: departureId,
        arrivalAirportId: arrivalId,
        numberOfTravellers: searchData.numberOfTravellers,
        cabinClass: searchData.cabinClass,
        departureDate: formattedDate
      }

      onSearch?.(searchParams)
    }
  }

  const handleSwapAirports = () => {
    airportsTouched.current = true
    setSearchError("")
    setSearchData(prev => ({
      ...prev,
      departureAirportId: prev.arrivalAirportId,
      arrivalAirportId: prev.departureAirportId
    }))
  }

  const formatDate = (date) => {
    if (!date) return { day: "--", month: "Month", year: "", weekday: "" }
    return {
      day: date.getDate().toString().padStart(2, '0'),
      month: date.toLocaleDateString("en-US", { month: "short" }),
      year: date.getFullYear().toString(),
      weekday: date.toLocaleDateString("en-US", { weekday: "short" })
    }
  }



  const adjustPassengers = (increment) => {
    setSearchData(prev => ({
      ...prev,
      numberOfTravellers: Math.max(1, Math.min(9, prev.numberOfTravellers + increment))
    }))
  }

  return (
    <div className={cn(
      "bg-white rounded-3xl shadow-2xl overflow-hidden border border-gray-100",
      className
    )}>
      {/* Trip Type Tabs */}
      <div className="bg-gradient-to-br from-slate-50 via-white to-blue-50/30 px-3 sm:px-8 py-6 border-b border-gray-200">
        <div className="flex space-x-2 bg-white rounded-2xl p-2 shadow-sm border border-gray-200">
          <button
            disabled title="Round trips are not available yet"
            className={cn(
              "px-3 sm:px-8 py-3 rounded-xl text-sm font-bold transition-all duration-300 relative overflow-hidden",
              tripType === "roundTrip"
                ? "bg-gradient-to-r from-blue-600 to-indigo-600 text-white shadow-lg shadow-blue-500/30"
                : "text-gray-600 hover:text-gray-900 hover:bg-gray-50"
            )}
          >
            Round Trip
          </button>
          <button
            onClick={() => setTripType("oneWay")}
            className={cn(
              "px-3 sm:px-8 py-3 rounded-xl text-sm font-bold transition-all duration-300",
              tripType === "oneWay"
                ? "bg-gradient-to-r from-blue-600 to-indigo-600 text-white shadow-lg shadow-blue-500/30"
                : "text-gray-600 hover:text-gray-900 hover:bg-gray-50"
            )}
          >
            One Way
          </button>
          <button
            disabled title="Multi-city trips are not available yet"
            className={cn(
              "px-3 sm:px-8 py-3 rounded-xl text-sm font-bold transition-all duration-300",
              tripType === "multiCity"
                ? "bg-gradient-to-r from-blue-600 to-indigo-600 text-white shadow-lg shadow-blue-500/30"
                : "text-gray-600 hover:text-gray-900 hover:bg-gray-50"
            )}
          >
            Multi City
          </button>
        </div>
      </div>

      <div className="p-3 sm:p-8">
        {searchError && <p role="alert" className="text-red-600 mb-4">{searchError}</p>}
        {/* Combined Flight Search Section - All in One Line */}
        <div className="bg-gradient-to-r from-gray-50 to-blue-50/30 rounded-2xl p-1 mb-6">
          <div className="flex flex-wrap items-center justify-between gap-y-3">
            {/* From */}
            <div className="relative group min-w-0 w-[calc(50%-2.5rem)] lg:w-0 lg:flex-1">
              <div className="p-2 sm:p-4 rounded-2xl bg-white hover:shadow-lg transition-all duration-300 border-2 border-transparent hover:border-blue-400 h-full">
                <div className="flex items-start gap-3">
                  <div className="hidden sm:block shrink-0 p-2 bg-gradient-to-br from-blue-500 to-blue-600 rounded-lg shadow-lg">
                    <Plane className="h-5 w-5 text-white" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <label className="text-xs font-bold text-gray-500 uppercase tracking-widest mb-1 block">From</label>
                    <AirportPicker
                      label="From" airports={airports} value={Number(searchData.departureAirportId) || null}
                      excludedId={Number(searchData.arrivalAirportId) || null}
                      onChange={id => selectAirport("departureAirportId", id)} loading={airportsLoading} error={airportsError}
                    />
                  </div>
                </div>
              </div>
            </div>

            {/* Swap Button */}
            <div className="flex justify-center items-center px-2">
              <button
                aria-label="Swap departure and arrival airports"
                onClick={handleSwapAirports}
                className="h-12 w-12 rounded-xl bg-gradient-to-br from-blue-600 via-indigo-600 to-purple-600 text-white hover:shadow-2xl hover:shadow-blue-500/50 hover:scale-110 transition-all duration-300 flex items-center justify-center group relative overflow-hidden"
              >
                <div className="absolute inset-0 bg-gradient-to-br from-purple-600 to-blue-600 opacity-0 group-hover:opacity-100 transition-opacity duration-300"></div>
                <ArrowLeftRight className="h-5 w-5 relative z-10 group-hover:rotate-180 transition-transform duration-300" />
              </button>
            </div>

            {/* To */}
            <div className="relative group min-w-0 w-[calc(50%-2.5rem)] lg:w-0 lg:flex-1">
              <div className="p-2 sm:p-4 rounded-2xl bg-white hover:shadow-lg transition-all duration-300 border-2 border-transparent hover:border-green-400 h-full">
                <div className="flex items-start gap-3">
                  <div className="hidden sm:block shrink-0 p-2 bg-gradient-to-br from-green-500 to-emerald-600 rounded-lg shadow-lg">
                    <Plane className="h-5 w-5 text-white rotate-90" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <label className="text-xs font-bold text-gray-500 uppercase tracking-widest mb-1 block">To</label>
                    <AirportPicker
                      label="To" airports={airports} value={Number(searchData.arrivalAirportId) || null}
                      excludedId={Number(searchData.departureAirportId) || null}
                      onChange={id => selectAirport("arrivalAirportId", id)} loading={airportsLoading} error={airportsError}
                    />
                  </div>
                </div>
              </div>
            </div>

            {/* Departure Date */}
            <Dialog open={departureDateOpen} onOpenChange={setDepartureDateOpen}>
              <DialogTrigger asChild>
                <div className="p-4 rounded-2xl bg-white hover:shadow-lg transition-all duration-300 cursor-pointer border-2 border-transparent hover:border-orange-400 group h-full">
                  <div className="flex items-start gap-2">
                    <div className="p-2 bg-gradient-to-br from-orange-500 to-orange-600 rounded-lg shadow-lg">
                      <CalendarDays className="h-4 w-4 text-white" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <label className="text-xs font-bold text-orange-700 uppercase tracking-widest mb-1 block">Departure</label>
                      <div className="flex items-baseline gap-1">
                        <div className="text-xl font-black text-gray-900">
                          {formatDate(searchData.departureDate).day}
                        </div>
                        <div className="text-xs font-bold text-gray-700">
                          {formatDate(searchData.departureDate).month}
                        </div>
                      </div>

                      <div className="text-xs text-gray-500">
                        {searchData.departureDate ? formatDate(searchData.departureDate).weekday : "Select"}
                      </div>

                    </div>
                  </div>
                </div>
              </DialogTrigger>
              <DialogContent className="w-auto p-0">
                <DialogHeader className="p-6 pb-0">
                  <DialogTitle className="text-2xl font-bold">Select departure date</DialogTitle>
                </DialogHeader>
                <Calendar
                  mode="single"
                  selected={searchData.departureDate}
                  captionLayout="dropdown"
                  onSelect={(date) => {
                    setSearchData(prev => ({ ...prev, departureDate: date }))
                    setDepartureDateOpen(false)
                  }}
                  disabled={(date) => date < new Date().setHours(0, 0, 0, 0)}
                  initialFocus
                  className="p-6"
                />
              </DialogContent>
            </Dialog>




            {/* Passengers */}
            <Dialog open={passengersOpen} onOpenChange={setPassengersOpen}>
              <DialogTrigger asChild>
                <div className="p-4 rounded-2xl bg-white hover:shadow-lg transition-all duration-300 cursor-pointer border-2 border-transparent hover:border-pink-400 group h-full">
                  <div className="flex items-start gap-2">
                    <div className="p-2 bg-gradient-to-br from-pink-500 to-pink-600 rounded-lg shadow-lg">
                      <Users className="h-4 w-4 text-white" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <label className="text-xs font-bold text-pink-700 uppercase tracking-widest mb-1 block">Travelers</label>
                      <div className="text-xl font-black text-gray-900">
                        {searchData.numberOfTravellers}
                      </div>
                      <div className="text-xs font-medium text-gray-500">
                        {searchData.numberOfTravellers === 1 ? "Traveller" : "Travellers"}
                      </div>
                    </div>
                  </div>
                </div>
              </DialogTrigger>
              <DialogContent className="sm:max-w-md">
                <DialogHeader>
                  <DialogTitle className="text-2xl font-bold">Select passengers</DialogTitle>
                </DialogHeader>
                <div className="py-8">
                  <div className="flex items-center justify-between bg-gray-50 p-6 rounded-2xl">
                    <div>
                      <div className="font-bold text-lg text-gray-900">Passengers</div>
                      <div className="text-sm text-gray-500">Age 12+</div>
                    </div>
                    <div className="flex items-center gap-5">
                      <button
                        onClick={() => adjustPassengers(-1)}
                        disabled={searchData.numberOfTravellers <= 1}
                        className="h-12 w-12 rounded-xl border-2 border-gray-300 bg-white hover:border-blue-500 hover:bg-blue-50 disabled:opacity-50 disabled:cursor-not-allowed font-bold text-xl transition-all hover:scale-110"
                      >
                        -
                      </button>
                      <span className="w-14 text-center text-3xl font-black text-gray-900">
                        {searchData.numberOfTravellers}
                      </span>
                      <button
                        onClick={() => adjustPassengers(1)}
                        disabled={searchData.numberOfTravellers >= 9}
                        className="h-12 w-12 rounded-xl border-2 border-gray-300 bg-white hover:border-blue-500 hover:bg-blue-50 disabled:opacity-50 disabled:cursor-not-allowed font-bold text-xl transition-all hover:scale-110"
                      >
                        +
                      </button>
                    </div>
                  </div>
                </div>
                <Button
                  onClick={() => setPassengersOpen(false)}
                  className="w-full h-14 text-lg font-bold bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-700 hover:to-indigo-700 rounded-xl shadow-lg"
                >
                  Done
                </Button>
              </DialogContent>
            </Dialog>

            {/* Cabin Class */}
            <div className="p-4 rounded-2xl bg-white hover:shadow-lg transition-all duration-300 border-2 border-transparent hover:border-cyan-400 group h-full">
              <div className="flex items-start gap-2">
                <div className="p-2 bg-gradient-to-br from-cyan-500 to-cyan-600 rounded-lg shadow-lg">
                  <Plane className="h-4 w-4 text-white" />
                </div>
                <div className="min-w-0 flex-1">
                  <label className="text-xs font-bold text-cyan-700 uppercase tracking-widest mb-1 block">Class</label>
                  <Select
                    value={searchData.cabinClass}
                    onValueChange={(value) => setSearchData(prev => ({ ...prev, cabinClass: value }))}
                  >
                    <SelectTrigger className="border-0 shadow-none p-0 h-auto focus:ring-0 bg-transparent">
                      <div className="text-left">
                        <div className="text-sm font-black text-gray-900">
                          <SelectValue />
                        </div>
                      </div>
                    </SelectTrigger>
                    <SelectContent>
                      {cabinClasses.map((cabin) => (
                        <SelectItem key={cabin.value} value={cabin.value} className="py-3 cursor-pointer">
                          <div className="font-bold text-base">{cabin.label}</div>
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* Special Fares */}
        <div className="mt-8 mb-6">
          <div className="flex items-center gap-2 mb-4">
            <span className="text-sm font-bold text-gray-700 uppercase tracking-wider">Select Special Fares</span>
            <div className="h-px flex-1 bg-gradient-to-r from-gray-300 to-transparent"></div>
          </div>
          <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-3">
            {specialFares.map((fare) => {
              const Icon = fare.icon
              return (
                <button
                  key={fare.id}
                  type="button" aria-pressed={specialFare === fare.id} onClick={() => setSpecialFare(fare.id)}
                  className={cn(
                    "relative p-4 rounded-xl border-2 transition-all duration-300 text-left group hover:shadow-md",
                    specialFare === fare.id
                      ? "border-blue-500 bg-blue-50 shadow-md shadow-blue-500/20"
                      : "border-gray-200 bg-white hover:border-blue-300"
                  )}
                >
                  {/* Check Icon */}
                  {specialFare === fare.id && (
                    <div className="absolute top-2 right-2 w-5 h-5 bg-blue-600 rounded-full flex items-center justify-center">
                      <Check className="h-3 w-3 text-white" />
                    </div>
                  )}

                  {/* Icon */}
                  <div className={cn(
                    "mb-2 p-2 rounded-lg w-fit transition-colors",
                    specialFare === fare.id
                      ? "bg-blue-600 text-white"
                      : "bg-gray-100 text-gray-600 group-hover:bg-blue-100 group-hover:text-blue-600"
                  )}>
                    <Icon className="h-4 w-4" />
                  </div>

                  {/* Label */}
                  <div className={cn(
                    "font-bold text-sm mb-1",
                    specialFare === fare.id ? "text-blue-700" : "text-gray-900"
                  )}>
                    {fare.label}
                  </div>

                  {/* Description */}
                  <div className={cn(
                    "text-xs leading-tight",
                    specialFare === fare.id ? "text-blue-600" : "text-gray-500"
                  )}>
                    {fare.description}
                  </div>

                  {/* Discount Badge */}
                  {fare.discount && (
                    <div className="mt-2">
                      <span className={cn(
                        "text-xs font-bold px-2 py-1 rounded-full",
                        specialFare === fare.id
                          ? "bg-blue-600 text-white"
                          : "bg-green-100 text-green-700"
                      )}>
                        {fare.discount}
                      </span>
                    </div>
                  )}
                </button>
              )
            })}
          </div>
        </div>

        {/* Search Button */}
        <Button
          onClick={handleSearch}
          className="w-full h-20 text-xl font-black bg-gradient-to-r from-blue-600 via-indigo-600 to-purple-600 hover:from-blue-700 hover:via-indigo-700 hover:to-purple-700 rounded-2xl shadow-2xl shadow-blue-500/30 hover:shadow-blue-600/50 disabled:shadow-none transition-all duration-300 hover:scale-[1.02] relative overflow-hidden group"
          disabled={!searchData.departureAirportId || !searchData.arrivalAirportId || !searchData.departureDate}
        >
          <div className="absolute inset-0 bg-gradient-to-r from-purple-600 via-pink-600 to-blue-600 opacity-0 group-hover:opacity-100 transition-opacity duration-500"></div>
          <div className="relative z-10 flex items-center justify-center gap-3">
            <Search className="h-7 w-7" />
            <span>Search Flights</span>
          </div>
        </Button>
      </div>
    </div>
  )
}

export default FlightSearchBar
