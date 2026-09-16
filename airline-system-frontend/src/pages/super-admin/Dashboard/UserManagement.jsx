import React, { useEffect, useMemo, useState } from "react";
import { useDispatch, useSelector } from "react-redux";
import { getAllUsers } from "@/Redux/user/userThunks";
import {
  Users,
  Search,
  Shield,
  User,
  Plane,
  RefreshCw,
  ChevronUp,
  ChevronDown,
  Mail,
  Phone,
  Clock,
} from "lucide-react";

/* ─────────────────────── helpers ─────────────────────── */
const ROLE_META = {
  ROLE_SYSTEM_ADMIN: { label: "System Admin", color: "bg-purple-100 text-purple-700" },
  ROLE_AIRLINE_OWNER: { label: "Airline Owner", color: "bg-blue-100 text-blue-700" },
  ROLE_USER: { label: "Passenger", color: "bg-green-100 text-green-700" },
};

function getRoleMeta(role) {
  return ROLE_META[role] || { label: role || "Unknown", color: "bg-gray-100 text-gray-600" };
}

function formatDate(dateStr) {
  if (!dateStr) return "Never";
  try {
    return new Date(dateStr).toLocaleDateString("en-IN", {
      day: "2-digit",
      month: "short",
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    });
  } catch {
    return "—";
  }
}

/* ─────────────────────── stat card ─────────────────────── */
function StatCard(props) {
  const { icon: Icon, label, value, color } = props;
  return (
    <div className={`rounded-xl p-4 flex items-center gap-4 ${color}`}>
      <div className="p-3 bg-white/40 rounded-lg">
        <Icon className="h-5 w-5" />
      </div>
      <div>
        <p className="text-2xl font-bold">{value}</p>
        <p className="text-sm opacity-80">{label}</p>
      </div>
    </div>
  );
}

/* ─────────────────────── sort header ─────────────────────── */
function SortHeader({ label, field, sortField, sortDir, onSort }) {
  const active = sortField === field;
  return (
    <th
      className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider cursor-pointer select-none hover:text-gray-700"
      onClick={() => onSort(field)}
    >
      <span className="flex items-center gap-1">
        {label}
        <span className="flex flex-col">
          <ChevronUp
            className={`h-3 w-3 -mb-1 ${active && sortDir === "asc" ? "text-indigo-600" : "text-gray-300"}`}
          />
          <ChevronDown
            className={`h-3 w-3 ${active && sortDir === "desc" ? "text-indigo-600" : "text-gray-300"}`}
          />
        </span>
      </span>
    </th>
  );
}

/* ─────────────────────── main component ─────────────────────── */
const UserManagement = () => {
  const dispatch = useDispatch();
  const { users, usersLoading, error, usersTotalElements = 0, usersTotalPages = 0 } = useSelector((s) => s.user);

  const [search, setSearch] = useState("");
  const [query, setQuery] = useState({ page: 0, size: 25, search: "", role: "all", sortBy: "fullName", direction: "asc" });
  const { page, size, role: roleFilter, sortBy: sortField, direction: sortDir } = query;

  useEffect(() => {
    const timer = setTimeout(() => {
      setQuery((previous) => previous.search === search.trim() ? previous : { ...previous, page: 0, search: search.trim() });
    }, 300);
    return () => clearTimeout(timer);
  }, [search]);

  useEffect(() => {
    const request = dispatch(getAllUsers(query));
    return () => request.abort();
  }, [dispatch, query]);

  function handleSort(field) {
    setQuery((previous) => ({ ...previous, page: 0, sortBy: field,
      direction: previous.sortBy === field && previous.direction === "asc" ? "desc" : "asc" }));
  }

  /* stats derived from real data */
  const stats = useMemo(() => {
    const total = usersTotalElements;
    const passengers = users.filter((u) => u.role === "ROLE_USER").length;
    const admins = users.filter((u) => u.role === "ROLE_AIRLINE_OWNER").length;
    const superAdmins = users.filter((u) => u.role === "ROLE_SYSTEM_ADMIN").length;
    return { total, passengers, admins, superAdmins };
  }, [users, usersTotalElements]);

  const filtered = users;
  const uniqueRoles = Object.keys(ROLE_META);

  return (
    <div className="space-y-6">
      {/* header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900 flex items-center gap-2">
            <Users className="h-6 w-6 text-indigo-600" />
            User Management
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            All registered users across the platform
          </p>
        </div>
        <button
          onClick={() => setQuery((previous) => ({ ...previous }))}
          disabled={usersLoading}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white rounded-lg text-sm font-medium hover:bg-indigo-700 disabled:opacity-60 transition"
        >
          <RefreshCw className={`h-4 w-4 ${usersLoading ? "animate-spin" : ""}`} />
          Refresh
        </button>
      </div>

      {/* stat cards */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
        <StatCard icon={Users} label="Matching Users" value={stats.total} color="bg-indigo-100 text-indigo-700" />
        <StatCard icon={User} label="Passengers on Page" value={stats.passengers} color="bg-green-100 text-green-700" />
        <StatCard icon={Plane} label="Airline Owners on Page" value={stats.admins} color="bg-blue-100 text-blue-700" />
        <StatCard icon={Shield} label="System Admins on Page" value={stats.superAdmins} color="bg-purple-100 text-purple-700" />
      </div>

      {/* filters */}
      <div className="bg-white rounded-xl border border-gray-200 p-4 flex flex-col sm:flex-row gap-3">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" />
          <input
            type="text"
            placeholder="Search by name, email or phone…"
            aria-label="Search users"
            maxLength={100}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full pl-9 pr-4 py-2 border border-gray-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400"
          />
        </div>
        <select
          value={roleFilter}
          aria-label="Filter by role"
          onChange={(e) => setQuery((previous) => ({ ...previous, page: 0, role: e.target.value }))}
          className="px-3 py-2 border border-gray-200 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-indigo-400 bg-white"
        >
          <option value="all">All Roles</option>
          {uniqueRoles.map((r) => (
            <option key={r} value={r}>
              {getRoleMeta(r).label}
            </option>
          ))}
        </select>
      </div>

      {/* table */}
      <div className="bg-white rounded-xl border border-gray-200 overflow-hidden">
        {usersLoading ? (
          <div className="flex flex-col items-center justify-center py-20 text-gray-400">
            <RefreshCw className="h-8 w-8 animate-spin mb-3 text-indigo-400" />
            <p className="text-sm">Loading users…</p>
          </div>
        ) : error ? (
          <div className="flex flex-col items-center justify-center py-20 text-red-400">
            <p className="font-medium">Failed to load users</p>
            <p className="text-sm mt-1">{error}</p>
          </div>
        ) : filtered.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-20 text-gray-400">
            <Users className="h-10 w-10 mb-3 opacity-40" />
            <p className="font-medium">No users found</p>
            <p className="text-sm mt-1">Try adjusting your search or filter</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
                    #
                  </th>
                  <SortHeader label="Name" field="fullName" sortField={sortField} sortDir={sortDir} onSort={handleSort} />
                  <SortHeader label="Email" field="email" sortField={sortField} sortDir={sortDir} onSort={handleSort} />
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
                    Role
                  </th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">
                    Phone
                  </th>
                  <SortHeader label="Last Login" field="lastLogin" sortField={sortField} sortDir={sortDir} onSort={handleSort} />
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {filtered.map((user, idx) => {
                  const roleMeta = getRoleMeta(user.role);
                  return (
                    <tr key={user.id} className="hover:bg-gray-50 transition-colors">
                      <td className="px-4 py-3 text-gray-400 text-xs">{page * size + idx + 1}</td>
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-3">
                          <div className="h-8 w-8 rounded-full bg-indigo-100 flex items-center justify-center text-indigo-600 font-semibold text-xs flex-shrink-0">
                            {(user.fullName || user.email || "?")[0].toUpperCase()}
                          </div>
                          <div>
                            <p className="font-medium text-gray-900">{user.fullName || "—"}</p>
                            {user.username && (
                              <p className="text-xs text-gray-400">@{user.username}</p>
                            )}
                          </div>
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        <span className="flex items-center gap-1 text-gray-600">
                          <Mail className="h-3.5 w-3.5 text-gray-400" />
                          {user.email || "—"}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${roleMeta.color}`}>
                          {roleMeta.label}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span className="flex items-center gap-1 text-gray-600">
                          <Phone className="h-3.5 w-3.5 text-gray-400" />
                          {user.phone || "—"}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span className="flex items-center gap-1 text-gray-500 text-xs">
                          <Clock className="h-3.5 w-3.5 text-gray-400" />
                          {formatDate(user.lastLogin)}
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3 text-sm text-gray-600">
        <span>Showing {usersTotalElements === 0 ? 0 : page * size + 1}–{Math.min((page + 1) * size, usersTotalElements)} of {usersTotalElements} users</span>
        <label className="flex items-center gap-2">
          Rows per page
          <select aria-label="Rows per page" value={size} onChange={(e) => setQuery((previous) => ({ ...previous, page: 0, size: Number(e.target.value) }))} className="rounded border p-2">
            {[25, 50, 100].map((value) => <option key={value} value={value}>{value}</option>)}
          </select>
        </label>
        <div className="flex items-center gap-3">
          <button disabled={usersLoading || page === 0} onClick={() => setQuery((previous) => ({ ...previous, page: previous.page - 1 }))} className="rounded border px-3 py-2 disabled:opacity-40">Previous</button>
          <span>Page {page + 1} of {Math.max(1, usersTotalPages)}</span>
          <button disabled={usersLoading || page + 1 >= usersTotalPages} onClick={() => setQuery((previous) => ({ ...previous, page: previous.page + 1 }))} className="rounded border px-3 py-2 disabled:opacity-40">Next</button>
        </div>
      </div>
    </div>
  );
};

export default UserManagement;
