// The aircraft endpoint returns an owner-scoped list; also accept paged deployments.
export function aircraftPage(aircrafts, { page = 0, size = 10, search = "", status, sortBy = "code", sortDirection = "asc" } = {}) {
  const query = search.trim().toLowerCase();
  const matches = aircrafts.filter(aircraft => (!status || aircraft.status === status) &&
    (!query || [aircraft.code, aircraft.model, aircraft.manufacturer].some(value => String(value || "").toLowerCase().includes(query))));
  matches.sort((a, b) => {
    const left = a[sortBy] ?? ""; const right = b[sortBy] ?? "";
    const order = typeof left === "number" && typeof right === "number" ? left - right : String(left).localeCompare(String(right));
    return sortDirection === "desc" ? -order : order;
  });
  const totalElements = matches.length;
  const totalPages = Math.ceil(totalElements / size);
  const number = Math.min(Math.max(0, page), Math.max(0, totalPages - 1));
  const content = matches.slice(number * size, (number + 1) * size);
  return { content, totalElements, totalPages, size, number, first: number === 0, last: number >= totalPages - 1, numberOfElements: content.length };
}
