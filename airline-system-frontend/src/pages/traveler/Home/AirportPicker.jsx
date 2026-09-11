import * as React from "react"
import { Check, ChevronDown } from "lucide-react"
import { Input } from "@/components/ui/input"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"

const normalize = value => String(value || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/đ/gi, "d").toLowerCase().trim()
const locationLabel = airport => [airport?.city?.name || airport?.name, airport?.city?.countryName].filter(Boolean).join(", ")

export default function AirportPicker({ label, airports, value, excludedId, onChange, loading = false, error = null }) {
  const [open, setOpen] = React.useState(false)
  const [query, setQuery] = React.useState("")
  const [activeIndex, setActiveIndex] = React.useState(0)
  const listId = React.useId()
  const selected = airports.find(airport => Number(airport.id) === value)
  const terms = normalize(query).split(/\s+/).filter(Boolean)
  const matches = airports.filter(airport => Number(airport.id) !== excludedId &&
    terms.every(term => normalize(`${airport.iataCode} ${airport.city?.name || ""} ${airport.city?.countryName || ""} ${airport.name || ""}`).includes(term)))
  const visible = matches.slice(0, 50)
  const active = Math.min(activeIndex, Math.max(0, visible.length - 1))
  const optionRefs = React.useRef([])

  React.useEffect(() => {
    if (open) optionRefs.current[active]?.scrollIntoView?.({ block: "nearest" })
  }, [open, active, query])

  const changeOpen = next => {
    setOpen(next)
    if (next) { setQuery(""); setActiveIndex(0) }
  }
  const select = airport => {
    onChange(Number(airport.id))
    changeOpen(false)
  }
  const onKeyDown = event => {
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault()
      setActiveIndex(index => Math.max(0, Math.min(visible.length - 1, index + (event.key === "ArrowDown" ? 1 : -1))))
    } else if (event.key === "Enter") {
      event.preventDefault()
      if (!loading && !error && visible[active]) select(visible[active])
    } else if (event.key === "Escape") {
      event.preventDefault()
      changeOpen(false)
    }
  }

  return (
    <Popover open={open} onOpenChange={changeOpen}>
      <PopoverTrigger asChild>
        <button type="button" title={selected ? locationLabel(selected) : undefined} aria-label={`${label}: ${selected ? `${selected.iataCode} ${locationLabel(selected)}` : "Select airport"}`} aria-haspopup="dialog" aria-expanded={open}
          className="flex min-w-0 w-full items-center gap-2 text-left rounded focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500">
          <span className="min-w-0 flex-1">
            <span className="block text-2xl font-black text-gray-900">{selected?.iataCode || "---"}</span>
            <span className="block text-xs font-medium text-gray-600 truncate">{locationLabel(selected) || "Select City"}</span>
          </span>
          <ChevronDown className="h-4 w-4 shrink-0" />
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" role="dialog" aria-label={`Choose ${label.toLowerCase()} airport`} className="w-[min(450px,calc(100vw-2rem))] p-3">
        <Input role="combobox" aria-label={`Search ${label.toLowerCase()} airports`} aria-expanded={open} aria-autocomplete="list" aria-controls={listId}
          aria-activedescendant={!loading && !error && visible.length ? `${listId}-${visible[active].id}` : undefined}
          placeholder="City, country, airport name or code" value={query} onChange={event => { setQuery(event.target.value); setActiveIndex(0) }} onKeyDown={onKeyDown} />
        <div role="status" className="text-sm text-muted-foreground py-2">
          {loading ? "Loading airports…" : error ? "Unable to load airports. Please try again." : matches.length === 0 ? "No airports found" : matches.length > 50 ? `Showing 50 of ${matches.length} airports. Type more to refine your search.` : `${matches.length} airports found`}
        </div>
        <div id={listId} role="listbox" aria-label={`${label} airports`} className="max-h-72 overflow-y-auto">
          {!loading && !error && visible.map((airport, index) => (
            <div key={airport.id} id={`${listId}-${airport.id}`} role="option" aria-selected={Number(airport.id) === value}
              ref={element => { optionRefs.current[index] = element }} onMouseMove={() => setActiveIndex(index)} onMouseDown={event => event.preventDefault()} onClick={() => select(airport)}
              className={`flex items-center gap-3 rounded-lg p-3 cursor-pointer ${index === active ? "bg-blue-50" : "hover:bg-muted"}`}>
              <div className="min-w-0 flex-1">
                <div className="font-semibold [overflow-wrap:anywhere]">{airport.iataCode} – {locationLabel(airport)}</div>
                <div className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{airport.name}</div>
              </div>
              {Number(airport.id) === value && <Check className="h-4 w-4 shrink-0" />}
            </div>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  )
}
