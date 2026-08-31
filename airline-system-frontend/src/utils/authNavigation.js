export const homeForRole = (role) => role === 'ROLE_SYSTEM_ADMIN' ? '/super-admin'
  : role === 'ROLE_AIRLINE_OWNER' ? '/airline' : '/traveler';

export function getLoginDestination(role, from) {
  if (typeof from !== 'string' || !from.startsWith('/') || from.startsWith('//') || from.includes('\\')) return homeForRole(role);
  if (/^\/airline(?:\/|$)/.test(from)) return role === 'ROLE_AIRLINE_OWNER' ? from : homeForRole(role);
  if (/^\/super-admin(?:\/|$)/.test(from)) return role === 'ROLE_SYSTEM_ADMIN' ? from : homeForRole(role);
  return /^\/(?:bookings|profile|booking-review|booking-success\/[^/?#]+|view-ticket\/[^/?#]+|payment(?:-cancelled\/[^/?#]+)?|ticket(?:\/[^/?#]+)?)(?:[?#]|$)/.test(from)
    ? from : homeForRole(role);
}
