import * as React from "react"
import { Plane, Loader2, RefreshCw } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { cn } from "@/lib/utils"
import { useNavigate } from "react-router-dom"
import { useDispatch, useSelector } from "react-redux"
import { getTravelerBookingPage } from "@/Redux/booking/bookingThunk"
import TravellerBookingCard from "./TravellerBookingCard"

const BookingHistory = () => {
  const { travelerBookingPage: bookingPage, travelerBookingPageLoading: loading,
    travelerBookingPageError: error } = useSelector(state => state.booking)
  const navigate = useNavigate()
  const dispatch = useDispatch()
  const [query, setQuery] = React.useState({ tab: "all", page: 0, size: 10 })
  const [refreshKey, setRefreshKey] = React.useState(0)
  const { tab: activeTab, page, size } = query
  const bookings = bookingPage?.content || []
  const totalElements = bookingPage?.totalElements || 0
  const totalPages = bookingPage?.totalPages || 0
  const refresh = () => setRefreshKey(previous => previous + 1)

  React.useEffect(() => {
    const request = dispatch(getTravelerBookingPage(query))
    return () => request.abort()
  }, [dispatch, query, refreshKey])

  React.useEffect(() => {
    if (!loading && !error && bookingPage?.number === page && page > 0 && page >= totalPages) {
      setQuery(previous => ({ ...previous, page: Math.max(0, totalPages - 1) }))
    }
  }, [loading, error, bookingPage, page, totalPages])

  return (
    <div className="min-h-screen bg-background">
      {/* Header */}
      <div className="bg-background border-b border-border sticky top-0 z-40">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
          <div className="flex flex-col gap-4">
            <div className="flex items-center justify-between">
              <div>
                <h1 className="text-2xl font-bold text-foreground">My Bookings</h1>
                <p className="text-muted-foreground">Manage your flight bookings and travel history</p>
              </div>
              <Button onClick={() => navigate("/traveler")} className="flex items-center gap-2">
                <Plane className="h-4 w-4" />
                Book New Flight
              </Button>
            </div>

            {/* Tabs */}
            <div className="flex space-x-1 bg-muted rounded-lg p-1 w-fit">
              {[
                { id: "all", label: "All Bookings" },
                { id: "upcoming", label: "Upcoming" },
                { id: "past", label: "Past" },
                { id: "cancelled", label: "Cancelled" }
              ].map((tab) => (
                <button
                  key={tab.id}
                  aria-pressed={activeTab === tab.id}
                  onClick={() => setQuery(previous => ({ ...previous, tab: tab.id, page: 0 }))}
                  className={cn(
                    "px-4 py-2 rounded-md text-sm font-medium transition-all",
                    activeTab === tab.id
                      ? "bg-background text-primary shadow-sm"
                      : "text-muted-foreground hover:text-foreground"
                  )}
                >
                  {tab.label}
                </button>
              ))}
            </div>
          </div>
        </div>
      </div>

      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
        <div className="">
          

          {/* Main Content */}
          <div className="">
            <div className="flex justify-end mb-4">
              <Button variant="outline" disabled={loading} onClick={refresh}><RefreshCw className="h-4 w-4 mr-2" />Refresh</Button>
            </div>
            <div className="space-y-4">
              {loading ? (
                <div role="status" className="text-center py-12"><Loader2 className="h-8 w-8 animate-spin mx-auto mb-4" />Loading bookings...</div>
              ) : error ? (
                <Card><CardContent className="py-12 text-center" role="alert">
                  <p className="text-destructive mb-4">{error}</p><Button onClick={refresh}>Retry</Button>
                </CardContent></Card>
              ) : bookings.length === 0 ? (
                <Card>
                  <CardContent className="py-12 text-center">
                    <Plane className="h-12 w-12 text-muted-foreground mx-auto mb-4" />
                    <h3 className="text-lg font-medium text-foreground mb-2">No bookings found</h3>
                    <p className="text-muted-foreground">You have no bookings in this category.</p>
                  </CardContent>
                </Card>
              ) : (
                bookings.map((booking) => (
                  <TravellerBookingCard key={booking.id} booking={booking} navigate={navigate} />
                ))
              )}
            </div>
            <nav aria-label="Booking history pagination" className="flex flex-wrap items-center justify-between gap-3 mt-6">
              <span className="text-sm text-muted-foreground" aria-live="polite">
                {loading ? "Loading..." : error ? "Unable to load bookings" : `Showing ${bookings.length ? page * size + 1 : 0} to ${Math.min(page * size + bookings.length, totalElements)} of ${totalElements} bookings`}
              </span>
              <div className="flex flex-wrap items-center gap-3">
                <Select value={String(size)} onValueChange={value => setQuery(previous => ({ ...previous, size: Number(value), page: 0 }))}>
                  <SelectTrigger className="w-32" aria-label="Bookings per page"><SelectValue /></SelectTrigger>
                  <SelectContent>{[10, 20, 50].map(value => <SelectItem key={value} value={String(value)}>{value} per page</SelectItem>)}</SelectContent>
                </Select>
                <Button variant="outline" disabled={loading || page === 0} onClick={() => setQuery(previous => ({ ...previous, page: previous.page - 1 }))}>Previous</Button>
                <span>Page {page + 1} of {Math.max(1, totalPages)}</span>
                <Button variant="outline" disabled={loading || !!error || page + 1 >= totalPages} onClick={() => setQuery(previous => ({ ...previous, page: previous.page + 1 }))}>Next</Button>
              </div>
            </nav>
          </div>
        </div>
      </div>
    </div>
  )
}



export default BookingHistory
