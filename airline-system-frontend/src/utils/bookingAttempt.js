const STORAGE_KEY = 'bookingAttempts';

const canonicalize = (value) => {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonicalize(value[key])]));
  }
  return value;
};

export async function bookingRequestFingerprint(data) {
  const bytes = new TextEncoder().encode(JSON.stringify(canonicalize(data)));
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

// Store only a hash and UUID, never passenger details. Keep successful attempts
// too: a refresh or a second click must still recover the same checkout.
export function getBookingAttemptKey(fingerprint, userId) {
  const attempts = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || '{}');
  const identity = `${userId}:${fingerprint}`;
  if (!attempts[identity]) {
    attempts[identity] = crypto.randomUUID();
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(attempts));
  }
  return attempts[identity];
}

export function startBookingAttempt() {
  sessionStorage.removeItem(STORAGE_KEY);
}
