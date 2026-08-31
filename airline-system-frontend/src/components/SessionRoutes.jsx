import React, { useEffect } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useDispatch, useSelector } from 'react-redux';
import { getUserProfile } from '@/Redux/user/userThunks';
import { initializeAnonymous, sessionExpired } from '@/Redux/auth/authSlice';
import { clearSessionStorage } from '@/Redux/sessionState';
import { homeForRole, getLoginDestination } from '@/utils/authNavigation';

const loading = <div role="status" className="p-8 text-center">Checking your session…</div>;

export function SessionInitializer() {
  const dispatch = useDispatch();
  useEffect(() => {
    const token = localStorage.getItem('jwt');
    const request = token ? dispatch(getUserProfile(token)) : dispatch(initializeAnonymous());
    // A JWT changed in another tab belongs to a new session. Revalidate it after cleanup.
    const onStorage = (event) => {
      if (event.key !== 'jwt' && event.key !== null) return;
      const nextToken = localStorage.getItem('jwt');
      clearSessionStorage({ preserveToken: true });
      dispatch(sessionExpired());
      if (nextToken) {
        dispatch(getUserProfile(nextToken));
      }
    };
    window.addEventListener('storage', onStorage);
    return () => { request?.abort?.(); window.removeEventListener('storage', onStorage); };
  }, [dispatch]);
  return null;
}

export function RequireSession({ roles }) {
  const { initialized, isAuthenticated, user } = useSelector((state) => state.auth);
  const location = useLocation();
  if (!initialized) return loading;
  if (!isAuthenticated || !user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  if (roles && !roles.includes(user.role)) return <Navigate to="/" replace />;
  return <Outlet />;
}

export function AuthProtected({ children }) {
  const { initialized, isAuthenticated, user } = useSelector((state) => state.auth);
  const location = useLocation();
  if (!initialized) return loading;
  if (isAuthenticated && user) return <Navigate to={getLoginDestination(user.role, location.state?.from)} replace />;
  return children;
}

export function RoleRedirect() {
  const { initialized, isAuthenticated, user } = useSelector((state) => state.auth);
  return initialized && isAuthenticated && user ? <Navigate to={homeForRole(user.role)} replace /> : null;
}

export function OnboardingGuard({ children }) {
  const { initialized, isAuthenticated, user } = useSelector((state) => state.auth);
  if (!initialized) return loading;
  if (isAuthenticated && user?.role !== 'ROLE_AIRLINE_OWNER') return <Navigate to="/" replace />;
  return children;
}
