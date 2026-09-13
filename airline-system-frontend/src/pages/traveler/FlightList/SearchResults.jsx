import * as React from "react";
import { useSearchParams, useNavigate } from "react-router-dom";
import { useSelector, useDispatch } from "react-redux";
import { SlidersHorizontal, Menu, X, Bookmark } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

// Import new components
import SearchSummaryBar from "./SearchSummaryBar";
import FiltersSidebar from "./Filterssidebar";
import SortingBar from "./SortingBar";
import ModernFlightCard from "./ModernFlightCard";
import {
  SearchResultsLoading,
  NoResultsFound,
} from "./LoadingStates";

import FlightSearchBar from "@/pages/traveler/Home/FlightSearchBar";
import { searchFlightsAvailability } from "@/Redux/flightSearch/flightSearchThunk";
import "./animations.css";
import { listAllAirports } from "@/Redux/airport/airportThunk";
import { useEffect } from "react";
import { parseDateOnly, formatDateOnly } from "@/utils/dateOnly";
import { clearSearchResults, invalidateSearchRequest } from "@/Redux/flightSearch/flightSearchSlice";
import { getAirlinesForDropdown } from "@/Redux/airline/airlineThunks";

const SearchResults = () => {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const previousCriteria = React.useRef(null);
  const previousRequest = React.useRef(null);
  const [settledRequest, setSettledRequest] = React.useState(null);
  const [retryAttempt, setRetryAttempt] = React.useState(0);
  const activeSearch = React.useRef(null);
  const dispatch = useDispatch();
  const { dropdownAirlines } = useSelector((store) => store.airline);

  // Redux state
  const {
    searchResults,
    loading: searchLoading,
    error,
    errorPage,
    loadingMore,
  } = useSelector((state) => state.flightSearch);

  const { airports } = useSelector((state) => state.airport);

  // Extract search parameters and map to API format
  const searchData = {
    departureAirportId: Number(searchParams.get("departureAirportId")) || "",
    arrivalAirportId: Number(searchParams.get("arrivalAirportId")) || "",
    departureDate: searchParams.get("departureDate")
      ? parseDateOnly(searchParams.get("departureDate"))
      : null,
    returnDate: searchParams.get("returnDate")
      ? parseDateOnly(searchParams.get("returnDate"))
      : null,
    passengers: Number(searchParams.get("numberOfTravellers") || searchParams.get("passengers") || "1"),
    cabinClass: searchParams.get("cabinClass") || "ECONOMY",
  };

  // console.log("Search Data: ----- ", searchData);

  const [sortBy, setSortBy] = React.useState("price");
  const [sortOrder, setSortOrder] = React.useState("asc");
  const [viewMode, setViewMode] = React.useState("list");
  const [showMobileFilters, setShowMobileFilters] = React.useState(false);
  const [bookmarkedFlights, setBookmarkedFlights] = React.useState(new Set());

  // Enhanced filters state
  const [filters, setFilters] = React.useState({
    airlines: [],
    priceRange: { min: 50, max: 20000 },
    departureTimeRange: "any",
    arrivalTimeRange: "any",
  });

  // Build filter parameters for backend API
  const buildFilterParams = (currentFilters) => {
    const params = {};

    // airlines: List<Long> — only send when user has selected specific airlines
    if (currentFilters.airlines.length > 0) {
      params.airlines = currentFilters.airlines;
    }

    // minPrice / maxPrice — only send when user has moved from default bounds
    if (currentFilters.priceRange.min > 50) {
      params.minPrice = currentFilters.priceRange.min;
    }

    if (currentFilters.priceRange.max < 20000) {
      params.maxPrice = currentFilters.priceRange.max;
    }

    if (currentFilters.departureTimeRange !== "any") {
      params.departureTimeRange = currentFilters.departureTimeRange;
    }

    if (currentFilters.arrivalTimeRange !== "any") {
      params.arrivalTimeRange = currentFilters.arrivalTimeRange;
    }

    return params;
  };

  // Build sort parameters for backend API
  const buildSortParams = () => {
    return {
      sortBy: sortBy,
      sortOrder: sortOrder,
    };
  };

  const handleSearch = (data) => {
    const params = new URLSearchParams({
      departureAirportId: String(data.departureAirportId),
      arrivalAirportId: String(data.arrivalAirportId),
      departureDate: formatDateOnly(data.departureDate),
      numberOfTravellers: String(data.numberOfTravellers),
      cabinClass: data.cabinClass || "ECONOMY",
    });
    navigate(`/search-results?${params}`);
  };

  // Load airports on component mount
  React.useEffect(() => {
    if (airports.length === 0) {
      dispatch(listAllAirports());
    }
  }, [dispatch, airports.length]);

  useEffect(() => {
    dispatch(getAirlinesForDropdown());
  }, [dispatch]);

  const baseParams = {
    departureAirportId: searchData.departureAirportId,
    arrivalAirportId: searchData.arrivalAirportId,
    departureDate: formatDateOnly(searchData.departureDate),
    passengers: searchData.passengers,
    cabinClass: searchData.cabinClass,
  };
  const criteriaKey = JSON.stringify(baseParams);
  const requestParams = {
    ...baseParams, ...buildFilterParams(filters), ...buildSortParams(), size: 20,
  };
  const requestKey = JSON.stringify(requestParams);
  const operationKey = `${requestKey}:${retryAttempt}`;
  const criteriaChanged = previousCriteria.current !== criteriaKey;
  const validSearch = Number.isSafeInteger(requestParams.departureAirportId) && requestParams.departureAirportId > 0 &&
    Number.isSafeInteger(requestParams.arrivalAirportId) && requestParams.arrivalAirportId > 0 &&
    requestParams.departureAirportId !== requestParams.arrivalAirportId && Boolean(searchData.departureDate) &&
    Number.isInteger(searchData.passengers) && searchData.passengers >= 1 && searchData.passengers <= 9 &&
    ["ECONOMY", "PREMIUM_ECONOMY", "BUSINESS", "FIRST"].includes(searchData.cabinClass);

  const updating = validSearch && (criteriaChanged || previousRequest.current !== operationKey || settledRequest !== operationKey || searchLoading);
  const displayedResults = criteriaChanged ? { content: [], last: true } : searchResults;
  const displayedError = !updating && !criteriaChanged ? error : null;
  const bookingDisabled = updating || Boolean(displayedError && !(errorPage > 0));

  React.useEffect(() => {
    let cancelled = false;
    previousRequest.current = operationKey;
    setSettledRequest(null);
    if (previousCriteria.current !== criteriaKey || !validSearch) {
      dispatch(clearSearchResults());
      previousCriteria.current = criteriaKey;
    } else {
      // Invalidate immediately, before the debounce, so late pages cannot append.
      dispatch(invalidateSearchRequest());
    }
    if (!validSearch) return;
    const timeout = setTimeout(() => {
      const request = dispatch(searchFlightsAvailability({ ...JSON.parse(requestKey), page: 0 }));
      activeSearch.current = request;
      Promise.resolve(request).then(() => {
        if (!cancelled) setSettledRequest(operationKey);
      });
    }, 300);
    return () => {
      cancelled = true;
      clearTimeout(timeout);
      activeSearch.current?.abort();
      activeSearch.current = null;
    };
  }, [dispatch, criteriaKey, requestKey, operationKey, validSearch]);

  const loadMore = () => {
    if (loadingMore || bookingDisabled || searchResults.last) return;
    activeSearch.current = dispatch(searchFlightsAvailability({ ...requestParams, page: searchResults.number + 1 }));
  };

  // Handler functions
  const handleBookmark = React.useCallback((flight) => {
    setBookmarkedFlights((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(flight.id)) {
        newSet.delete(flight.id);
      } else {
        newSet.add(flight.id);
      }
      return newSet;
    });
  }, []);

  const clearAllFilters = () => {
    setFilters({
      airlines: [],
      priceRange: { min: 50, max: 20000 },
      departureTimeRange: "any",
      arrivalTimeRange: "any",
    });
  };

  // Get unique airlines for filter from flights data

  // Get airport names for search data display
  const getDepartureAirport = () =>
    airports.find((a) => a.id === searchData.departureAirportId);
  const getArrivalAirport = () =>
    airports.find((a) => a.id === searchData.arrivalAirportId);

  if (!validSearch) return <div role="alert" className="p-8">Invalid search. Choose different airports, a valid date and one to nine passengers. <Button onClick={() => navigate("/traveler")}>Back to search</Button></div>;


  return (
    <div className="min-h-screen relative overflow-hidden">
      {/* Two-Tone Background - Goibibo Style */}
      {/* Top 30% - Darker/Colored Section */}
      <div className="absolute top-0 left-0 right-0 h-[30vh] bg-gradient-to-br from-blue-900 via-blue-950 to-[#1B1464]">
        {/* Decorative elements for top section */}
        <div className="absolute inset-0 overflow-hidden">
          <div className="absolute top-10 left-10 w-64 h-64 bg-white/5 rounded-full blur-3xl animate-pulse"></div>
          <div
            className="absolute top-20 right-20 w-80 h-80 bg-indigo-400/10 rounded-full blur-3xl animate-pulse"
            style={{ animationDelay: "2s" }}
          ></div>

          {/* Subtle Pattern */}
          <div
            className="absolute inset-0 opacity-[0.03]"
            style={{
              backgroundImage: `radial-gradient(circle at 2px 2px, white 1px, transparent 0)`,
              backgroundSize: "32px 32px",
            }}
          ></div>
        </div>
      </div>

      {/* Bottom 70% - Light Section */}
      <div className="absolute top-[30vh] left-0 right-0 bottom-0 bg-gradient-to-br from-gray-50 via-blue-50/30 to-gray-400">
        {/* Decorative elements for bottom section */}
        <div className="absolute inset-0 overflow-hidden pointer-events-none">
          <div
            className="absolute top-40 left-1/4 w-96 h-96 bg-blue-400/5 rounded-full blur-3xl animate-pulse"
            style={{ animationDelay: "3s" }}
          ></div>
          <div
            className="absolute bottom-20 right-1/4 w-80 h-80 bg-purple-400/5 rounded-full blur-3xl animate-pulse"
            style={{ animationDelay: "5s" }}
          ></div>

          {/* Subtle Pattern */}
          <div
            className="absolute inset-0 opacity-[0.02]"
            style={{
              backgroundImage: `radial-gradient(circle at 2px 2px, currentColor 1px, transparent 0)`,
              backgroundSize: "40px 40px",
            }}
          ></div>
        </div>
      </div>

      {/* Smooth Transition Wave Between Sections */}
      <div className="absolute top-[29vh] left-0 right-0 h-[3vh] pointer-events-none z-0">
        <svg
          className="absolute bottom-0 w-full h-full"
          viewBox="0 0 1440 120"
          preserveAspectRatio="none"
        >
          <path
            fill="currentColor"
            className="text-gray-50"
            d="M0,64L48,69.3C96,75,192,85,288,80C384,75,480,53,576,48C672,43,768,53,864,64C960,75,1056,85,1152,80C1248,75,1344,53,1392,42.7L1440,32L1440,120L1392,120C1344,120,1248,120,1152,120C1056,120,960,120,864,120C768,120,672,120,576,120C480,120,384,120,288,120C192,120,96,120,48,120L0,120Z"
          ></path>
        </svg>
      </div>

      <div className="relative z-10 max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
        {/* Mobile Filter Button */}
        <div className="lg:hidden mb-4">
          <Button
            variant="outline"
            onClick={() => setShowMobileFilters(!showMobileFilters)}
            className="w-full flex items-center justify-center gap-2 bg-white/90 backdrop-blur-md hover:bg-white transition-all shadow-md hover:shadow-lg border-blue-200"
          >
            <SlidersHorizontal className="h-4 w-4" />
            Filters & Sort
            {showMobileFilters ? (
              <X className="h-4 w-4 ml-auto" />
            ) : (
              <Menu className="h-4 w-4 ml-auto" />
            )}
          </Button>
        </div>

        <FlightSearchBar key={criteriaKey} initialSearchData={searchData} onSearch={handleSearch} className="mb-6" />

        {/* Search Summary Bar */}
        <div className="z-10 pb-3">
          <SearchSummaryBar
            searchData={{
              from: getDepartureAirport()?.iataCode || "",
              to: getArrivalAirport()?.iataCode || "",
              departureDate: searchData.departureDate,
              returnDate: searchData.returnDate,
              passengers: searchData.passengers,
              cabinClass: searchData.cabinClass.toLowerCase().replace("_", "-"),
            }}
            onModifySearch={handleSearch}
          />
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-4 gap-6">
          {/* Enhanced Filters Sidebar */}
          <div
            className={cn(
              "lg:col-span-1",
              showMobileFilters ? "block" : "hidden lg:block",
            )}
          >
            <div className="relative">
                {/* Decorative gradient behind filter */}
                <div className="absolute -inset-2 bg-gradient-to-br from-blue-500/10 to-purple-500/10 rounded-2xl blur-xl"></div>
                <div className="relative">
                  <FiltersSidebar
                    filters={filters}
                    onFiltersChange={setFilters}
                    airlines={dropdownAirlines}
                    className="animate-slide-in-left bg-white/80 backdrop-blur-md shadow-xl"
                  />
                </div>
            </div>
          </div>

          {/* Flight Results Section */}
          <div className="lg:col-span-3 space-y-4" aria-busy={updating}>
            {/* Enhanced Sorting Bar with glow effect */}
            <div className="relative">
              <div className="absolute -inset-1 bg-gradient-to-r from-blue-500/20 to-purple-500/20 rounded-xl blur-lg"></div>
              <div className="relative">
                <SortingBar
                  sortBy={sortBy}
                  sortOrder={sortOrder}
                  onSortChange={setSortBy}
                  onSortOrderChange={setSortOrder}
                  resultsCount={displayedResults?.content?.length}
                  viewMode={viewMode}
                  onViewModeChange={setViewMode}
                  className="animate-slide-in-top bg-white/90 backdrop-blur-md shadow-lg"
                />
              </div>
            </div>

            {updating && <p role="status" className="text-sm text-muted-foreground">{displayedResults?.content?.length ? "Updating flights…" : "Searching flights…"}</p>}
            {displayedError && <div role="alert" className="text-red-600">
              <p>{displayedError}</p>
              <Button variant="outline" disabled={loadingMore} onClick={() => errorPage > 0 ? loadMore() : setRetryAttempt(attempt => attempt + 1)}>Try Again</Button>
            </div>}
            {/* Flight Results with enhanced cards */}
            {updating && !displayedResults?.content?.length ? (
              <SearchResultsLoading count={5} />
            ) : displayedError && !displayedResults?.content?.length ? null : displayedResults?.content?.length === 0 ? (
              <NoResultsFound onClearFilters={clearAllFilters} />
            ) : (
              <div
                className={cn(
                  viewMode === "grid"
                    ? "grid grid-cols-1 xl:grid-cols-2 gap-4"
                    : "space-y-4",
                )}
              >
                {displayedResults?.content?.map((flight, index) => (
                  <div
                    key={flight.id}
                    className="animate-fade-in relative group"
                    style={{ animationDelay: `${index * 100}ms` }}
                  >
                    {/* Subtle hover glow effect */}
                    <div className="absolute -inset-1 bg-gradient-to-r from-blue-500/0 to-purple-500/0 group-hover:from-blue-500/20 group-hover:to-purple-500/20 rounded-2xl blur-lg transition-all duration-300 opacity-0 group-hover:opacity-100"></div>
                    <div className="relative">
                      <ModernFlightCard
                        flight={flight}
                        bookingDisabled={bookingDisabled}
                        cabinClass={searchData?.cabinClass}
                        onBookmark={handleBookmark}
                        isBookmarked={bookmarkedFlights.has(flight.id)}
                        viewMode={viewMode}
                        className="card-hover bg-white/90 backdrop-blur-sm shadow-lg hover:shadow-2xl transition-all duration-300"
                      />
                    </div>
                  </div>
                ))}
              </div>
            )}

            {/* Load the next backend page */}
            {displayedResults?.content?.length > 0 && !displayedResults.last && (
              <div className="flex justify-center pt-8">
                <div className="relative group">
                  <div className="absolute -inset-1 bg-gradient-to-r from-blue-500 to-purple-500 rounded-xl blur opacity-30 group-hover:opacity-60 transition-opacity duration-300"></div>
                  <Button
                    variant="outline"
                    onClick={loadMore}
                    disabled={loadingMore || bookingDisabled}
                    className="relative px-8 py-3 bg-white backdrop-blur-sm hover:bg-white transition-all duration-200 shadow-lg hover:shadow-xl border-blue-200 font-semibold"
                  >
                    {loadingMore ? "Loading flights..." : "Load more flights"}
                  </Button>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Enhanced Floating Action Buttons */}
        <div className="fixed bottom-6 right-6 flex flex-col gap-3 z-40">
          {/* Scroll to Top Button */}
          <div className="relative group">
            <div className="absolute -inset-1 bg-gradient-to-br from-blue-500 to-purple-500 rounded-full blur opacity-40 group-hover:opacity-75 transition-opacity duration-300"></div>
            <Button
              variant="secondary"
              size="sm"
              className="relative rounded-full w-12 h-12 p-0 bg-white backdrop-blur-md shadow-xl hover:shadow-2xl transition-all duration-200 hover:scale-110 float-animation border-2 border-blue-200"
              onClick={() => window.scrollTo({ top: 0, behavior: "smooth" })}
            >
              <span className="text-lg">⬆️</span>
            </Button>
          </div>

          {/* Bookmarked Flights Button */}
          {bookmarkedFlights.size > 0 && (
            <div className="relative group">
              <div className="absolute -inset-1 bg-gradient-to-br from-pink-500 to-red-500 rounded-full blur opacity-50 group-hover:opacity-75 transition-opacity duration-300"></div>
              <Button
                variant="secondary"
                size="sm"
                className="relative rounded-full w-12 h-12 p-0 bg-gradient-to-br from-blue-600 to-indigo-600 text-white shadow-xl hover:shadow-2xl transition-all duration-200 hover:scale-110 float-animation border-2 border-blue-400"
                style={{ animationDelay: "1s" }}
              >
                <Bookmark className="h-5 w-5" />
                <span className="absolute -top-1 -right-1 bg-gradient-to-br from-red-500 to-pink-500 text-white text-xs font-bold rounded-full w-5 h-5 flex items-center justify-center shadow-lg ring-2 ring-white">
                  {bookmarkedFlights.size}
                </span>
              </Button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default SearchResults;
