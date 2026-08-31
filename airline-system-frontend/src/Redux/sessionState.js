export const isSessionBoundary = (action) => [
  'user/logout/pending', 'auth/login/pending', 'auth/signup/pending', 'auth/sessionExpired',
].includes(action.type);

// Each store owns its request generations. Old requests may finish, but cannot refill a new session.
export const createSessionMiddleware = () => {
  let generation = 0;
  const requests = new Map();
  return ({ getState }) => (next) => (action) => {
    if (isSessionBoundary(action)) generation += 1;
    const { requestId, requestStatus } = action.meta || {};
    if (requestStatus === 'pending') requests.set(requestId, generation);
    if (requestStatus === 'fulfilled' || requestStatus === 'rejected') {
      const startedIn = requests.get(requestId);
      requests.delete(requestId);
      if (startedIn !== undefined && startedIn !== generation) return action;
      if (action.type.startsWith('user/getProfile/') && getState().auth.profileRequestId !== requestId) return action;
    }
    return next(action);
  };
};

export function clearSessionStorage({ preserveToken = false } = {}) {
  if (!preserveToken) localStorage.removeItem('jwt');
  localStorage.removeItem('airline_onboarding_progress');
  sessionStorage.removeItem('paymentDetails');
}
