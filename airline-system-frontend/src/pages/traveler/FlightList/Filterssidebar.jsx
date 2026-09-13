import * as React from "react";
import {
  Filter,
  ChevronDown,
  ChevronUp,
  RefreshCw,
  Plane,
  Clock,
  DollarSign,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Checkbox } from "@/components/ui/checkbox";
import { RadioGroup, RadioGroupItem } from "@/components/ui/radio-group";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Slider } from "@/components/ui/slider";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { cn } from "@/lib/utils";
import { Avatar, AvatarFallback, AvatarImage } from "@/components/ui/avatar";

const MIN_PRICE = 50;
const MAX_PRICE = 20000;

const formatPrice = (value) =>
  new Intl.NumberFormat("en-IN", { maximumFractionDigits: 0 }).format(value);

// Keep this type stable so search updates preserve controls and their animations.
const FilterSection = ({ title, id, icon, children, count, expanded, onToggle }) => (
  <div className="border-b border-border last:border-b-0">
    <Button
      onClick={() => onToggle(id)}
      className="w-full flex items-center justify-between px-4 py-3 text-left rounded-none h-auto"
      variant="ghost"
    >
      <div className="flex items-center gap-2">
        {React.createElement(icon, { className: "h-4 w-4 text-primary" })}
        <span className="font-medium text-sm text-foreground">{title}</span>
        {count > 0 && (
          <Badge variant="default" className="h-5 px-1.5 text-xs bg-primary text-primary-foreground">
            {count}
          </Badge>
        )}
      </div>
      {expanded ? (
        <ChevronUp className="h-4 w-4 text-muted-foreground flex-shrink-0" />
      ) : (
        <ChevronDown className="h-4 w-4 text-muted-foreground flex-shrink-0" />
      )}
    </Button>
    {expanded && (
      <div className="px-4 pb-4 animate-in slide-in-from-top-1 fade-in duration-200">
        {children}
      </div>
    )}
  </div>
);

const FiltersSidebar = ({ filters, onFiltersChange, airlines, className }) => {
  const [expandedSections, setExpandedSections] = React.useState({
    price: true,
    airlines: true,
    departure: true,
    arrival: false,
  });

  const toggleSection = (section) => {
    setExpandedSections((prev) => ({ ...prev, [section]: !prev[section] }));
  };

  const updateFilter = (key, value) => {
    onFiltersChange((prev) => ({ ...prev, [key]: value }));
  };

  const clearAllFilters = () => {
    onFiltersChange({
      airlines: [],
      priceRange: { min: MIN_PRICE, max: MAX_PRICE },
      departureTimeRange: "any",
      arrivalTimeRange: "any",
    });
  };

  // Active filter counts per section
  const activeCounts = {
    price:
      filters.priceRange.min > MIN_PRICE || filters.priceRange.max < MAX_PRICE
        ? 1
        : 0,
    airlines: filters.airlines.length,
    departure: filters.departureTimeRange !== "any" ? 1 : 0,
    arrival: filters.arrivalTimeRange !== "any" ? 1 : 0,
  };

  const totalActive = Object.values(activeCounts).reduce((a, b) => a + b, 0);

  const timeRanges = [
    { value: "any", label: "Any Time", icon: "🕐", sub: "All departures" },
    { value: "morning", label: "Morning", icon: "🌅", sub: "6 AM – 12 PM" },
    { value: "afternoon", label: "Afternoon", icon: "☀️", sub: "12 PM – 6 PM" },
    { value: "evening", label: "Evening", icon: "🌆", sub: "6 PM – 12 AM" },
    { value: "night", label: "Night", icon: "🌙", sub: "12 AM – 6 AM" },
  ];

  return (
    <Card className={cn("h-fit sticky top-24 overflow-hidden", className)}>
      <CardHeader className="pb-2 pt-4 px-4">
        <CardTitle className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Filter className="h-4 w-4 text-primary" />
            <span className="text-base">Filters</span>
            {totalActive > 0 && (
              <Badge className="h-5 px-1.5 text-xs bg-primary/90">
                {totalActive} active
              </Badge>
            )}
          </div>
          <Button
            variant="ghost"
            size="sm"
            onClick={clearAllFilters}
            className="text-xs text-muted-foreground hover:text-destructive h-7 px-2"
          >
            <RefreshCw className="h-3 w-3 mr-1" />
            Reset
          </Button>
        </CardTitle>
      </CardHeader>

      <Separator />

      <CardContent className="p-0">
        {/* ── Price Range ─────────────────────────────── */}
        <FilterSection title="Price Range" id="price" icon={DollarSign} count={activeCounts.price} expanded={expandedSections.price} onToggle={toggleSection}>
          <div className="space-y-3 pt-1">
            {/* Current range label */}
            <div className="flex items-center justify-between">
              <span className="text-xs font-medium text-primary bg-primary/10 px-2 py-0.5 rounded-full">
                ${formatPrice(filters.priceRange.min)}
              </span>
              <span className="text-xs text-muted-foreground">to</span>
              <span className="text-xs font-medium text-primary bg-primary/10 px-2 py-0.5 rounded-full">
                ${formatPrice(filters.priceRange.max)}
              </span>
            </div>

            <Slider
              value={[filters.priceRange.min, filters.priceRange.max]}
              onValueChange={(val) =>
                updateFilter("priceRange", { min: val[0], max: val[1] })
              }
              min={MIN_PRICE}
              max={MAX_PRICE}
              step={50}
              className="w-full"
            />

            <div className="flex gap-2">
              <div className="flex-1">
                <Label className="text-xs text-muted-foreground mb-1 block">
                  Min ($)
                </Label>
                <Input
                  type="number"
                  value={filters.priceRange.min}
                  onChange={(e) =>
                    updateFilter("priceRange", {
                      ...filters.priceRange,
                      min: Math.max(
                        MIN_PRICE,
                        parseInt(e.target.value) || MIN_PRICE,
                      ),
                    })
                  }
                  className="h-8 text-sm"
                  min={MIN_PRICE}
                  max={MAX_PRICE}
                />
              </div>
              <div className="flex-1">
                <Label className="text-xs text-muted-foreground mb-1 block">
                  Max ($)
                </Label>
                <Input
                  type="number"
                  value={filters.priceRange.max}
                  onChange={(e) =>
                    updateFilter("priceRange", {
                      ...filters.priceRange,
                      max: Math.min(
                        MAX_PRICE,
                        parseInt(e.target.value) || MAX_PRICE,
                      ),
                    })
                  }
                  className="h-8 text-sm"
                  min={MIN_PRICE}
                  max={MAX_PRICE}
                />
              </div>
            </div>

            <div className="flex justify-between text-xs text-muted-foreground">
              <span>${formatPrice(MIN_PRICE)}</span>
              <span>${formatPrice(MAX_PRICE)}</span>
            </div>
          </div>
        </FilterSection>

        {/* ── Airlines ────────────────────────────────── */}
        <FilterSection title="Airlines" id="airlines" icon={Plane} count={activeCounts.airlines} expanded={expandedSections.airlines} onToggle={toggleSection}>
          {airlines && airlines.length > 0 ? (
            <ScrollArea className="h-[180px] pr-2">
              <div className="space-y-1 pt-1">
                {airlines.map((airline) => {
                  // Backend expects List<Long> – store airline.id (number)
                  const isChecked = filters.airlines.includes(airline.id);
                  return (
                    <div
                      key={airline.id}
                      className={cn(
                        "flex items-center gap-3 px-2 py-2 rounded-lg transition-colors cursor-pointer",
                        isChecked
                          ? "bg-primary/10 border border-primary/20"
                          : "hover:bg-muted/60",
                      )}
                      onClick={() => {
                        if (isChecked) {
                          updateFilter(
                            "airlines",
                            filters.airlines.filter((id) => id !== airline.id),
                          );
                        } else {
                          updateFilter("airlines", [
                            ...filters.airlines,
                            airline.id,
                          ]);
                        }
                      }}
                    >
                      <Checkbox
                        id={`airline-${airline.id}`}
                        checked={isChecked}
                        onCheckedChange={(checked) => {
                          if (checked) {
                            updateFilter("airlines", [
                              ...filters.airlines,
                              airline.id,
                            ]);
                          } else {
                            updateFilter(
                              "airlines",
                              filters.airlines.filter(
                                (id) => id !== airline.id,
                              ),
                            );
                          }
                        }}
                        onClick={(e) => e.stopPropagation()}
                        className="pointer-events-none"
                      />
                      <Avatar>
                        <AvatarImage src={airline.logoUrl} />
                        <AvatarFallback>{airline.iataCode}</AvatarFallback>
                      </Avatar>

                      <div className="flex-1 min-w-0">
                        <p className="text-sm text-foreground truncate">
                          {airline.name}
                        </p>
                        <p className="text-xs text-muted-foreground">
                          {airline.code}
                        </p>
                      </div>
                      {isChecked && (
                        <Badge
                          variant="secondary"
                          className="h-4 px-1 text-[10px] bg-primary/20 text-primary"
                        >
                          ✓
                        </Badge>
                      )}
                    </div>
                  );
                })}
              </div>
            </ScrollArea>
          ) : (
            <p className="text-xs text-muted-foreground pt-2">
              No airlines available.
            </p>
          )}
        </FilterSection>

        {/* ── Departure Time ──────────────────────────── */}
        <FilterSection title="Departure Time" id="departure" icon={Clock} count={activeCounts.departure} expanded={expandedSections.departure} onToggle={toggleSection}>
          <RadioGroup
            value={filters.departureTimeRange}
            onValueChange={(value) => updateFilter("departureTimeRange", value)}
            className="space-y-1 pt-1"
          >
            {timeRanges.map((range) => {
              const isSelected = filters.departureTimeRange === range.value;
              return (
                <div
                  key={range.value}
                  className={cn(
                    "flex items-center gap-3 px-2 py-2 rounded-lg transition-colors cursor-pointer",
                    isSelected
                      ? "bg-primary/10 border border-primary/20"
                      : "hover:bg-muted/60",
                  )}
                  onClick={() =>
                    updateFilter("departureTimeRange", range.value)
                  }
                >
                  <RadioGroupItem
                    value={range.value}
                    id={`dep-${range.value}`}
                    className="pointer-events-none"
                  />
                  <span className="text-base leading-none">{range.icon}</span>
                  <div className="flex-1">
                    <Label
                      htmlFor={`dep-${range.value}`}
                      className="text-sm font-medium cursor-pointer block"
                    >
                      {range.label}
                    </Label>
                    <p className="text-xs text-muted-foreground">{range.sub}</p>
                  </div>
                </div>
              );
            })}
          </RadioGroup>
        </FilterSection>

        {/* ── Arrival Time ────────────────────────────── */}
        <FilterSection title="Arrival Time" id="arrival" icon={Clock} count={activeCounts.arrival} expanded={expandedSections.arrival} onToggle={toggleSection}>
          <RadioGroup
            value={filters.arrivalTimeRange}
            onValueChange={(value) => updateFilter("arrivalTimeRange", value)}
            className="space-y-1 pt-1"
          >
            {timeRanges.map((range) => {
              const isSelected = filters.arrivalTimeRange === range.value;
              return (
                <div
                  key={range.value}
                  className={cn(
                    "flex items-center gap-3 px-2 py-2 rounded-lg transition-colors cursor-pointer",
                    isSelected
                      ? "bg-primary/10 border border-primary/20"
                      : "hover:bg-muted/60",
                  )}
                  onClick={() => updateFilter("arrivalTimeRange", range.value)}
                >
                  <RadioGroupItem
                    value={range.value}
                    id={`arr-${range.value}`}
                    className="pointer-events-none"
                  />
                  <span className="text-base leading-none">{range.icon}</span>
                  <div className="flex-1">
                    <Label
                      htmlFor={`arr-${range.value}`}
                      className="text-sm font-medium cursor-pointer block"
                    >
                      {range.label}
                    </Label>
                    <p className="text-xs text-muted-foreground">{range.sub}</p>
                  </div>
                </div>
              );
            })}
          </RadioGroup>
        </FilterSection>

      </CardContent>
    </Card>
  );
};

export default FiltersSidebar;
