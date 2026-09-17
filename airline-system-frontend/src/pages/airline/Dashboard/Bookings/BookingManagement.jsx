import * as React from "react";
import { useDispatch, useSelector } from "react-redux";
import { getAirlineBookingPage } from "@/Redux/booking/bookingThunk";
import {
  getAllFlightInstances
} from "@/Redux/flightInstance/flightInstanceThunk";
import {
  Search,
  Users,
  CreditCard,
  Download,
  Eye,
  RefreshCw,
  CheckCircle,
  XCircle,
  AlertCircle,
  Plane,
  Loader2,
  SortAsc,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { cn } from "@/lib/utils";
import BookingDetails from "./BookingDetails";

const BookingManagement = () => {
  const dispatch = useDispatch();
  const {
    airlineBookingPage: bookingPage,
    airlineBookingPageLoading: loading,
    airlineBookingPageError: error,
  } = useSelector((store) => store.booking);
  
  const { paginatedFlightInstances, loading: flightInstancesLoading } = useSelector(
      (store) => store.flightInstance,
    );

  const [query, setQuery] = React.useState({
    search: "", status: "all", flightInstanceId: "all", sortDirection: "DESC", page: 0, size: 20,
  });
  const [searchQuery, setSearchQuery] = React.useState("");
  const { status: statusFilter, flightInstanceId, sortDirection, page, size } = query;
  const changeFilter = (key, value) => setQuery(previous => ({ ...previous, [key]: value, page: 0 }));
  const [selectedBooking, setSelectedBooking] = React.useState(null);
  const [showBookingDetails, setShowBookingDetails] = React.useState(false);
  const [refreshKey, setRefreshKey] = React.useState(0);

  const flightInstances = paginatedFlightInstances?.content || [];
  React.useEffect(() => { dispatch(getAllFlightInstances()); }, [dispatch]);
  React.useEffect(() => {
    const timer = setTimeout(() => {
      setQuery(previous => previous.search === searchQuery.trim() ? previous : ({ ...previous, search: searchQuery.trim(), page: 0 }));
    }, 300);
    return () => clearTimeout(timer);
  }, [searchQuery]);

  React.useEffect(() => {
    const request = dispatch(getAirlineBookingPage({
      page: query.page, size: query.size, search: query.search || undefined,
      status: query.status === "all" ? undefined : query.status.toUpperCase(),
      flightInstanceId: query.flightInstanceId === "all" ? undefined : query.flightInstanceId,
      sortDirection: query.sortDirection,
    }));
    return () => request.abort();
  }, [dispatch, query, refreshKey]);

  const bookings = bookingPage?.content || [];
  const totalElements = bookingPage?.totalElements || 0;
  const totalPages = bookingPage?.totalPages || 0;
  React.useEffect(() => {
    if (!loading && !error && bookingPage?.number === page && page > 0 && page >= totalPages) {
      setQuery(previous => ({ ...previous, page: Math.max(0, totalPages - 1) }));
    }
  }, [loading, error, bookingPage, page, totalPages]);
  const refresh = () => setRefreshKey(previous => previous + 1);

  const formatTime = (dt) =>
    new Date(dt).toLocaleTimeString("en-US", {
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    });

  const formatDate = (dt) =>
    new Date(dt).toLocaleDateString("en-US", {
      month: "short",
      day: "numeric",
      year: "numeric",
    });

  const getStatusBadge = (status) => {
    const statusConfig = {
      CONFIRMED: { color: "bg-green-100 text-green-800", icon: CheckCircle },
      PENDING: { color: "bg-yellow-100 text-yellow-800", icon: AlertCircle },
      CANCELLED: { color: "bg-red-100 text-red-800", icon: XCircle },
      REFUNDED: { color: "bg-purple-100 text-purple-800", icon: RefreshCw },
    };
    const config =
      statusConfig[status?.toUpperCase()] || statusConfig["CONFIRMED"];
    const Icon = config.icon;
    return (
      <Badge className={cn("flex items-center gap-1 w-fit", config.color)}>
        <Icon className="h-3 w-3" />
        {status}
      </Badge>
    );
  };

  const getPaymentStatusBadge = (status) => {
    const statusConfig = {
      SUCCESS: { color: "bg-green-100 text-green-800" },
      PENDING: { color: "bg-yellow-100 text-yellow-800" },
      FAILED: { color: "bg-red-100 text-red-800" },
      REFUNDED: { color: "bg-blue-100 text-blue-800" },
    };
    const config =
      statusConfig[status?.toUpperCase()] || statusConfig["PENDING"];
    return <Badge className={cn("w-fit", config.color)}>{status}</Badge>;
  };

  const handleViewBooking = (booking) => {
    setSelectedBooking(booking);
    setShowBookingDetails(true);
  };

  const handleCancelBooking = (bookingId) => {
    if (window.confirm("Are you sure you want to cancel this booking?")) {
      // TODO: Implement cancel booking API call
      console.log("Cancel booking:", bookingId);
    }
  };

  const bookingStats = {
    total: totalElements,
    confirmed: bookings.filter((b) => b.status === "CONFIRMED").length,
    pending: bookings.filter((b) => b.status === "PENDING").length,
    cancelled: bookings.filter((b) => b.status === "CANCELLED").length,
    totalRevenue: bookings
      .filter((b) => b.payment)
      .reduce((sum, b) => sum + (b.totalAmount || 0), 0),
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold text-gray-900">
            Booking Management
          </h2>
          <p className="text-gray-600">
            Manage passenger bookings and reservations
          </p>
        </div>
        <Button
          variant="outline"
          onClick={refresh}
          disabled={loading}
        >
          <RefreshCw className="h-4 w-4 mr-2" />
          Refresh
        </Button>
      </div>

      {/* Stats Cards */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-gray-600">Total Bookings</p>
                <p className="text-2xl font-bold text-gray-900">
                  {loading || error ? "-" : bookingStats.total}
                </p>
              </div>
              <Users className="h-8 w-8 text-blue-600" />
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-gray-600">Confirmed on this page</p>
                <p className="text-2xl font-bold text-green-600">
                  {loading || error ? "-" : bookingStats.confirmed}
                </p>
              </div>
              <CheckCircle className="h-8 w-8 text-green-600" />
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-gray-600">Pending on this page</p>
                <p className="text-2xl font-bold text-yellow-600">
                  {loading || error ? "-" : bookingStats.pending}
                </p>
              </div>
              <AlertCircle className="h-8 w-8 text-yellow-600" />
            </div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-gray-600">Revenue on this page</p>
                <p className="text-2xl font-bold text-purple-600">
                  {loading || error ? "-" : `$${(bookingStats.totalRevenue / 100000).toFixed(1)}L`}
                </p>
              </div>
              <CreditCard className="h-8 w-8 text-purple-600" />
            </div>
          </CardContent>
        </Card>
      </div>

      {/* Bookings Table */}
      <Card>
        <CardHeader>
          <CardTitle>Bookings</CardTitle>
        </CardHeader>
        <CardContent>
          {/* Filters */}
          <div className="flex flex-col sm:flex-row gap-3 mb-6 flex-wrap">
            <div className="relative flex-1 min-w-[200px]">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" />
              <Input
                placeholder="Search by PNR, passenger, email..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="pl-10"
              />
            </div>

            {/* Status filter */}
            <Select value={statusFilter} onValueChange={value => changeFilter("status", value)}>
              <SelectTrigger className="w-full sm:w-40">
                <SelectValue placeholder="All Status" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">All Status</SelectItem>
                <SelectItem value="confirmed">Confirmed</SelectItem>
                <SelectItem value="pending">Pending</SelectItem>
                <SelectItem value="cancelled">Cancelled</SelectItem>
              </SelectContent>
            </Select>

            {/* Flight Instance filter */}
            <Select
              value={flightInstanceId}
              onValueChange={value => changeFilter("flightInstanceId", value)}
            >
              <SelectTrigger className="w-full sm:w-56">
                <Plane className="h-3.5 w-3.5 mr-1 text-gray-500 shrink-0" />
                <SelectValue placeholder="All Flights" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">All Flights</SelectItem>
                {flightInstancesLoading ? (
                  <SelectItem value="_loading" disabled>
                    Loading...
                  </SelectItem>
                ) : (
                  flightInstances.map((fi) => (
                    <SelectItem key={fi.id} value={String(fi.id)}>
                      {fi.flightNumber+ ` ` ||
                        fi.flight?.flightNumber  ||
                        `#${fi.id} `}
                        {fi.departureAirport?.city?.name} {` - `}
                        {fi.arrivalAirport?.city?.name}
                      {fi.departureDateTime
                        ? ` · ${new Date(fi.departureDateTime).toLocaleDateString("en-US", { month: "short", day: "numeric" })}`
                        : ""}
                    </SelectItem>
                  ))
                )}
              </SelectContent>
            </Select>

            {/* Sort direction */}
            <Select value={sortDirection} onValueChange={value => changeFilter("sortDirection", value)}>
              <SelectTrigger className="w-full sm:w-36">
                <SortAsc className="h-3.5 w-3.5 mr-1 text-gray-500 shrink-0" />
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="DESC">Latest first</SelectItem>
                <SelectItem value="ASC">Older first</SelectItem>
              </SelectContent>
            </Select>

            <Button variant="outline" className="flex items-center gap-2">
              <Download className="h-4 w-4" />
              Export
            </Button>
          </div>

          {/* Table */}
          {loading ? (
            <div role="status" className="text-center py-12"><Loader2 className="h-8 w-8 animate-spin mx-auto mb-4" />Loading bookings...</div>
          ) : error ? (
            <div role="alert" className="text-center py-12"><p className="text-red-600 mb-4">{error}</p><Button onClick={refresh}>Retry</Button></div>
          ) : bookings.length === 0 ? (
            <div className="text-center py-12">
              <Users className="h-12 w-12 text-gray-400 mx-auto mb-4" />
              <h3 className="text-lg font-medium text-gray-900 mb-2">
                No bookings found
              </h3>
              <p className="text-gray-600">
                Try adjusting your search criteria
              </p>
            </div>
          ) : (
            <div className="rounded-md border overflow-hidden">
              <Table>
                <TableHeader>
                  <TableRow className="bg-gray-50">
                    <TableHead className="font-semibold">Ref</TableHead>
                    <TableHead className="font-semibold">Flight</TableHead>
                    <TableHead className="font-semibold">Route</TableHead>
                    <TableHead className="font-semibold">
                      Passenger(s)
                    </TableHead>
                    <TableHead className="font-semibold">Departure</TableHead>
                    <TableHead className="font-semibold">Amount</TableHead>
                    <TableHead className="font-semibold">Status</TableHead>
                    <TableHead className="font-semibold">Payment</TableHead>
                    <TableHead className="font-semibold">Booked</TableHead>
                    <TableHead className="font-semibold text-right">
                      Actions
                    </TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {bookings.map((booking) => (
                    <TableRow
                      key={booking.id}
                      className="hover:bg-blue-50/40 transition-colors"
                    >
                      {/* Ref */}
                      <TableCell>
                        <span className="font-semibold text-blue-700 text-sm">
                          {booking.bookingReference || "—"}
                        </span>
                      </TableCell>

                      {/* Flight */}
                      <TableCell>
                        <div className="flex items-center gap-2">
                          <Plane className="h-4 w-4 text-gray-400 shrink-0" />
                          <div>
                            <div className="font-semibold text-sm text-gray-900">
                              {booking.flightNumber || booking.flightName}
                            </div>
                            {booking.flightName && booking.flightNumber && (
                              <div className="text-xs text-gray-500">
                                {booking.flightName}
                              </div>
                            )}
                          </div>
                        </div>
                      </TableCell>

                      {/* Route */}
                      <TableCell>
                        <div className="text-sm font-semibold text-gray-900">
                          {booking.departureAirport} → {booking.arrivalAirport}
                        </div>
                        <div className="text-xs text-gray-500">
                          {booking.flightDuration}
                        </div>
                      </TableCell>

                      {/* Passengers */}
                      <TableCell>
                        <div className="text-sm font-medium text-gray-900">
                          {booking.passengers?.[0]?.fullName || "—"}
                        </div>
                        {booking.totalPassengers > 1 && (
                          <div className="text-xs text-gray-500">
                            +{booking.totalPassengers - 1} more
                          </div>
                        )}
                      </TableCell>

                      {/* Departure */}
                      <TableCell>
                        <div className="text-sm font-semibold text-gray-900">
                          {formatTime(booking.departureTime)}
                        </div>
                        <div className="text-xs text-gray-500">
                          {formatDate(booking.departureTime)}
                        </div>
                        {booking.isUpcoming && (
                          <Badge className="bg-blue-100 text-blue-700 border-0 text-xs mt-1">
                            Upcoming
                          </Badge>
                        )}
                      </TableCell>

                      {/* Amount */}
                      <TableCell>
                        <span className="font-bold text-sm text-gray-900">
                          {booking.currency}{" "}
                          {booking.totalAmount?.toLocaleString()}
                        </span>
                      </TableCell>

                      {/* Booking Status */}
                      <TableCell>{getStatusBadge(booking.status)}</TableCell>

                      {/* Payment Status */}
                      <TableCell>
                        {getPaymentStatusBadge(booking.paymentStatus)}
                      </TableCell>

                      {/* Booked */}
                      <TableCell>
                        <div className="text-xs text-gray-600">
                          {formatDate(booking.bookingDate)}
                        </div>
                      </TableCell>

                      {/* Actions */}
                      <TableCell className="text-right">
                        <div className="flex items-center justify-end gap-1">
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => handleViewBooking(booking)}
                            className="hover:bg-blue-50 hover:text-blue-700"
                            title="View details"
                          >
                            <Eye className="h-4 w-4" />
                          </Button>
                          {booking.status !== "CANCELLED" && (
                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => handleCancelBooking(booking.id)}
                              className="hover:bg-red-50 text-red-500 hover:text-red-700"
                              title="Cancel booking"
                            >
                              <XCircle className="h-4 w-4" />
                            </Button>
                          )}
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
          <nav aria-label="Booking pagination" className="flex flex-wrap items-center justify-between gap-3 mt-4">
            <span className="text-sm text-gray-600" aria-live="polite">
              {loading ? "Loading..." : error ? "Unable to load bookings" : `Showing ${bookings.length ? page * size + 1 : 0}–${Math.min(page * size + bookings.length, totalElements)} of ${totalElements} bookings`}
            </span>
            <div className="flex items-center gap-3">
              <Select value={String(size)} onValueChange={value => changeFilter("size", Number(value))}>
                <SelectTrigger className="w-32" aria-label="Bookings per page"><SelectValue /></SelectTrigger>
                <SelectContent>{[10, 20, 50, 100].map(value => <SelectItem key={value} value={String(value)}>{value} per page</SelectItem>)}</SelectContent>
              </Select>
              <Button variant="outline" disabled={loading || page === 0} onClick={() => setQuery(previous => ({ ...previous, page: previous.page - 1 }))}>Previous</Button>
              <span>Page {page + 1} of {Math.max(1, totalPages)}</span>
              <Button variant="outline" disabled={loading || !!error || page + 1 >= totalPages} onClick={() => setQuery(previous => ({ ...previous, page: previous.page + 1 }))}>Next</Button>
            </div>
          </nav>
        </CardContent>
      </Card>

      {/* Booking Details Modal */}
      {showBookingDetails && selectedBooking && (
        <BookingDetails
          booking={selectedBooking}
          onClose={() => setShowBookingDetails(false)}
          getStatusBadge={getStatusBadge}
          getPaymentStatusBadge={getPaymentStatusBadge}
        />
      )}
    </div>
  );
};

export default BookingManagement;
