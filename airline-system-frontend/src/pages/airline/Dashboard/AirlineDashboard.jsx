import * as React from "react"
import { useLocation, useNavigate } from "react-router-dom"
import {
  Plus,
  Search,
  Filter,
  Calendar,
  Bell
} from "lucide-react"
import { Button } from "@/components/ui/button"

import { Badge } from "@/components/ui/badge"
import { ScrollArea } from "@/components/ui/scroll-area"
import { cn } from "@/lib/utils"
import AirlineSidebar from "../Sidebar/AirlineSidebar"
import AirlineRoutes from "../routes/AirlineRoutes"
import { useSelector, useDispatch } from "react-redux"
import { getFlightsByAirline } from "@/Redux/flight/flightThunk"
import { listAllAircrafts } from "@/Redux/aircraft/aircraftThunks"
import { getAllFlightSchedules } from "@/Redux/flightSchedule/flightScheduleThunk"
import { getBookingsByAirline, getBookingStatisticsForAirline } from "@/Redux/booking/bookingThunk"
import { formatCurrency } from "@/utils/formateCurrency"



const AirlineDashboard = () => {
  const location = useLocation()
  const navigate = useNavigate()
  const dispatch = useDispatch()

  // Determine active section from URL
  const getActiveSectionFromPath = (pathname) => {
    if (pathname === '/airline' || pathname === '/airline/' || pathname === '/airline/dashboard') return 'overview'
    if (pathname.includes('/aircraft')) return 'aircraft'
    if (pathname.includes('/baggage-polic')) return 'baggagePolicy'
    if (pathname.includes('/fare-rules')) return 'fareRules'
    if (pathname.includes('/fare/')) return 'flights'
    if (pathname.includes('/flight-cabin')) return 'flights'
    if (pathname.includes('/flights')) return 'flights'
    if (pathname.includes('/schedules')) return 'schedules'
    if (pathname.includes('/instances')) return 'instances'
    if (pathname.includes('/insurance-coverages')) return 'insuranceCoverages'
    if (pathname.includes('/ancillaries')) return 'ancillaries'
    if (pathname.includes('/meals')) return 'meals'
    if (pathname.includes('/coupons')) return 'pricing'
    if (pathname.includes('/pricing')) return 'pricing'
    if (pathname.includes('/bookings')) return 'bookings'
    if (pathname.includes('/transactions')) return 'transactions'
    if (pathname.includes('/profile')) return 'profile'
    if (pathname.includes('/route-performance')) return 'route-performance'
    if (pathname.includes('/airport-performance')) return 'airport-performance';
    if (pathname.includes('/reports')) return 'reports'
    return 'overview'
  }

  const activeSection = getActiveSectionFromPath(location.pathname)
  const [isSidebarCollapsed, setIsSidebarCollapsed] = React.useState(false)

  // Existing flight management state
  const { flights, flightPage, loading: flightsLoading, error: flightsError } = useSelector(state => state.flight)
  const fleet = useSelector(state => state.aircraft)
  const schedules = useSelector(state => state.flightSchedule)
  const booking = useSelector(state => state.booking)


  const [statusFilter, setStatusFilter] = React.useState("all")
  const [routeFilter, setRouteFilter] = React.useState("all")



  // Load flights on component mount
  React.useEffect(() => {
    dispatch(getFlightsByAirline());
    dispatch(listAllAircrafts());
    dispatch(getAllFlightSchedules());
  }, [dispatch])


  React.useEffect(() => {
    if (activeSection === 'overview') {
      dispatch(getBookingsByAirline());
      dispatch(getBookingStatisticsForAirline());
    }
  }, [dispatch, activeSection])




  const counts = {
    aircraft: fleet.fleetLoaded && !fleet.error ? fleet.aircrafts.length : null,
    flights: !flightsLoading && !flightsError ? flightPage?.totalElements ?? null : null,
    schedules: schedules.listLoaded && !schedules.error
      ? schedules.paginatedFlightSchedules?.totalElements ?? schedules.flightSchedules.length : null,
    'bookings-list': booking.ownerBookingsStatus === 'succeeded' ? booking.ownerBookings.length : null,
  }
  const dashboardStats = {
    totalFlights: counts.flights ?? 'Unavailable',
    scheduledFlights: counts.flights !== null && flights.length === counts.flights
      ? flights.filter(f => f.status === 'SCHEDULED').length : 'Unavailable',
    totalBookings: counts['bookings-list'] ?? 'Unavailable',
    // Flight definitions do not contain dated inventory or occupancy metrics.
    avgOccupancy: 'Unavailable',
    revenue: booking.statisticsStatus === 'succeeded' && booking.statistics?.revenueThisMonth != null
      ? formatCurrency(booking.statistics.revenueThisMonth) : 'Unavailable',
  }

  // Handle sidebar section changes
  const handleSectionChange = (sectionId) => {
    console.log("Section changed to:", sectionId)
    switch (sectionId) {
      // Dashboard
      case 'overview':
        navigate('/airline/dashboard')
        break

      // Aircraft Management
      case 'aircraft':
        navigate('/airline/aircraft')
        break
      case 'create-aircraft':
        navigate('/airline/aircraft/new')
        break

      // Flight Management
      case 'flights':
        navigate('/airline/flights')
        break
      case 'flights-create':
        navigate('/airline/flights/new')
        break
      case 'flight-cabin-create':
        navigate('/airline/flight-cabin/new')
        break

      // Flight Schedules
      case 'schedules':
        navigate('/airline/schedules')
        break
      case 'schedules-create':
        navigate('/airline/schedules/new')
        break

      // Flight Instances
      case 'instances':
        navigate('/airline/instances')
        break
      case 'instances-create':
        navigate('/airline/instances/new')
        break

      // Fare Management
      case 'fare-create':
        navigate('/airline/fare/new')
        break

      // Baggage Policy
      case 'baggage-policy-create':
        navigate('/airline/baggage-policies/new')
        break
      case 'baggage-policies':
        navigate('/airline/baggage-policies')
        break

      // Fare Rules
      case 'fare-rules':
        navigate('/airline/fare-rules')
        break
      case 'fare-rules-create':
        navigate('/airline/fare-rules/new')
        break
      case 'fare-rules-templates':
        navigate('/airline/fare-rules/templates')
        break

      // Ancillaries
      case 'ancillaries-catalog':
        navigate('/airline/ancillaries')
        break
      case 'ancillaries-create':
        navigate('/airline/ancillaries/create')
        break
      case 'insurance-coverages':
        navigate('/airline/insurance-coverages')
        break

      // Meals
      case 'meals-catalog':
      case 'meals':
        navigate('/airline/meals')
        break

      // Pricing & Discounts
      case 'pricing-base':
        navigate('/airline/pricing/base')
        break
      case 'pricing-dynamic':
        navigate('/airline/pricing/dynamic')
        break
      case 'coupons':
        navigate('/airline/coupons')
        break
      case 'coupons-create':
        navigate('/airline/coupons/new')
        break
      case 'discounts-list':
        navigate('/airline/pricing/discounts')
        break
      case 'discounts-create':
        navigate('/airline/pricing/discounts/create')
        break
      case 'pricing-rules':
        navigate('/airline/pricing/rules')
        break

      // Bookings
      case 'airline-profile':
        navigate('/airline/profile')
        break
      case 'transactions':
        navigate('/airline/transactions')
        break
      case 'bookings-list':
        navigate('/airline/bookings')
        break
      case 'bookings-payments':
        navigate('/airline/bookings/payments')
        break

      // Reports & Analytics
      case 'reports-sales':
        navigate('/airline/reports/sales')
        break
      case 'reports-occupancy':
        navigate('/airline/reports/occupancy')
        break
      case 'reports-revenue':
        navigate('/airline/reports/revenue')
        break
      case 'reports-performance':
        navigate('/airline/reports/performance')
        break
      case 'route-performance':
        navigate('/airline/route-performance')
        break
      case 'airport-performance':
        navigate('/airline/airport-performance')
        break
      case 'reports-custom':
        navigate('/airline/reports/custom')
        break

      default:
        navigate('/airline/dashboard')
    }
  }

  const toggleSidebar = () => {
    setIsSidebarCollapsed(!isSidebarCollapsed)
  }


  return (
    <div className="flex min-h-screen bg-background">
      {/* Sidebar */}
      <AirlineSidebar
        counts={counts}
        activeSection={activeSection}
        onSectionChange={handleSectionChange}
        isCollapsed={isSidebarCollapsed}
        onToggleCollapse={toggleSidebar}
      />

      {/* Main Content Area */}
      <div className={cn(
        "flex-1 transition-all duration-300 ease-in-out",
        isSidebarCollapsed ? "ml-16" : "ml-80"
      )}>
        {/* Header */}
        <div className="bg-background border-b border-border sticky top-0 z-30">
          <div className="px-6 py-4">
            <div className="flex items-center justify-between">
              <div>
                <h1 className="text-2xl font-bold text-foreground">
                  {activeSection === "overview" ? "Dashboard Overview" :
                    activeSection === "aircraft" ? "Aircraft Management" :
                      activeSection === "flights" ? "Flight Management" :
                        activeSection === "schedules" ? "Flight Schedules" :
                          activeSection === "instances" ? "Flight Instances" :
                            activeSection === "fareRules" ? "Fare Rules" :
                              activeSection === "baggagePolicy" ? "Baggage Policies" :
                                activeSection === "insuranceCoverages" ? "Insurance Coverage Management" :
                                  activeSection === "ancillaries" ? "Ancillaries" :
                                    activeSection === "meals" ? "Meal Management" :
                                      activeSection === "pricing" ? "Pricing & Discounts" :
                                        activeSection === "bookings" ? "Booking Management" :
                                        activeSection === "transactions" ? "Transactions" :
                                        activeSection === "profile" ? "Airline Profile" :
                                          activeSection === "route-performance" ? "Route Performance" :
                                            activeSection === "reports" ? "Reports & Analytics" :
                                              "Dashboard"}
                </h1>
                <p className="text-muted-foreground">
                  {activeSection === "overview" ? "Comprehensive overview of your airline operations" :
                    activeSection === "aircraft" ? "Manage aircraft details and configurations" :
                      activeSection === "flights" ? "Manage your flight schedules and operations" :
                        activeSection === "schedules" ? "Manage recurring flight schedules and templates" :
                          activeSection === "instances" ? "Manage flight instances and cabin configurations" :
                            activeSection === "fareRules" ? "Configure fare rules and restrictions" :
                              activeSection === "baggagePolicy" ? "Configure and manage baggage allowances for your airline" :
                                activeSection === "insuranceCoverages" ? "Manage and configure insurance coverages for your travel protection products" :
                                  activeSection === "ancillaries" ? "Manage ancillary services and add-ons" :
                                    activeSection === "meals" ? "Manage meal options and catering services" :
                                      activeSection === "pricing" ? "Configure pricing and promotional offers" :
                                        activeSection === "bookings" ? "View and manage passenger bookings" :
                                        activeSection === "transactions" ? "View and manage your airline payment transactions" :
                                        activeSection === "profile" ? "Manage your airline profile and contact information" :
                                          activeSection === "route-performance" ? "Analyze your most profitable and popular routes" :
                                            activeSection === "reports" ? "Analytics and performance insights" :
                                              "Manage your airline operations"}
                </p>
              </div>
              <div className="flex items-center gap-4">
                <Button variant="outline" className="flex items-center gap-2">
                  <Bell className="h-4 w-4" />
                  Notifications
                  <Badge className="ml-1 bg-red-100 text-red-800">3</Badge>
                </Button>

              </div>
            </div>

            {/* Quick Stats - Show on overview */}
            {activeSection === "overview" && (
              <div className="grid grid-cols-1 md:grid-cols-5 gap-4 mt-6">
                <div className="bg-gradient-to-br from-blue-50 to-blue-100 p-4 rounded-xl border border-blue-200">
                  <div className="text-2xl font-bold text-blue-600">{dashboardStats.totalFlights}</div>
                  <div className="text-sm text-blue-800">Total Flights</div>
                </div>
                <div className="bg-gradient-to-br from-green-50 to-green-100 p-4 rounded-xl border border-green-200">
                  <div className="text-2xl font-bold text-green-600">{dashboardStats.scheduledFlights}</div>
                  <div className="text-sm text-green-800">Scheduled Flights</div>
                </div>
                <div className="bg-gradient-to-br from-purple-50 to-purple-100 p-4 rounded-xl border border-purple-200">
                  <div className="text-2xl font-bold text-purple-600">{dashboardStats.totalBookings}</div>
                  <div className="text-sm text-purple-800">Total Bookings</div>
                </div>
                <div className="bg-gradient-to-br from-orange-50 to-orange-100 p-4 rounded-xl border border-orange-200">
                  <div className="text-2xl font-bold text-orange-600">{dashboardStats.avgOccupancy}</div>
                  <div className="text-sm text-orange-800">Avg Occupancy</div>
                </div>
                <div className="bg-gradient-to-br from-indigo-50 to-indigo-100 p-4 rounded-xl border border-indigo-200">
                  <div className="text-2xl font-bold text-indigo-600">{dashboardStats.revenue}</div>
                  <div className="text-sm text-indigo-800">Monthly Revenue</div>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Main Content */}
        <ScrollArea className="flex-1 p-6">
          <AirlineRoutes
            statusFilter={statusFilter}
            setStatusFilter={setStatusFilter}
            routeFilter={routeFilter}
            setRouteFilter={setRouteFilter}

          />
        </ScrollArea>
      </div>


    </div>
  )
}



export default AirlineDashboard

