import React from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { Plus } from 'lucide-react';
import { deleteFlight, getFlightManagementPage } from '@/Redux/flight/flightThunk';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Card, CardContent } from '@/components/ui/card';
import FlightCard from './FlightCard';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

const FlightManagement = () => {
  const dispatch = useDispatch();
  const navigate = useNavigate();
  const { managementPage, managementPageLoading, managementPageError } = useSelector(state => state.flight);
  const [query, setQuery] = React.useState({ page: 0, size: 20 });
  const [revision, setRevision] = React.useState(0);
  const [deletingId, setDeletingId] = React.useState(null);
  const flights = managementPage?.content || [];
  const total = managementPage?.totalElements || 0;
  const totalPages = managementPage?.totalPages || 0;
  const { page, size } = query;
  const loading = managementPageLoading || (!managementPage && !managementPageError);
  const busy = loading || deletingId !== null;
  const refresh = () => setRevision(value => value + 1);

  React.useEffect(() => {
    const request = dispatch(getFlightManagementPage({ page, size }));
    return () => request.abort();
  }, [dispatch, page, size, revision]);

  React.useEffect(() => {
    if (!managementPageLoading && !managementPageError && managementPage?.number === page
        && page > 0 && page >= totalPages) {
      setQuery(value => ({ ...value, page: Math.max(0, totalPages - 1) }));
    }
  }, [managementPageLoading, managementPageError, managementPage?.number, page, totalPages]);

  const handleDeleteFlight = async flightId => {
    if (deletingId !== null) return;
    setDeletingId(flightId);
    try {
      await dispatch(deleteFlight(flightId)).unwrap();
      refresh();
      toast.success('Flight deleted successfully');
    } catch (error) {
      toast.error(error || 'Failed to delete flight');
    } finally {
      setDeletingId(null);
    }
  };

  const handleAddNewFlight = () => navigate('/airline/flights/new');
  const handleViewFlight = flight => navigate(`/airline/flights/${flight.id}`);
  const handleEditFlight = flight => navigate(`/airline/flights/${flight.id}/edit`);

  return (
    <div className="space-y-6" aria-busy={busy}>
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-2xl font-bold text-foreground">Flight Management</h2>
          <p className="text-muted-foreground">Manage your flight schedules and operations</p>
        </div>
        <Button onClick={handleAddNewFlight} className="flex items-center gap-2">
          <Plus className="h-4 w-4" />Add New Flight
        </Button>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="outline" disabled={busy} onClick={refresh}>Refresh</Button>
        <Select value={String(size)} disabled={busy} onValueChange={value => setQuery({ page: 0, size: Number(value) })}>
          <SelectTrigger className="w-40" aria-label="Flights per page"><SelectValue /></SelectTrigger>
          <SelectContent>{[10, 20, 50, 100].map(value => <SelectItem key={value} value={String(value)}>{value} per page</SelectItem>)}</SelectContent>
        </Select>
      </div>
      {loading ? (
        <p role="status">Loading flights...</p>
      ) : managementPageError ? (
        <div role="alert">{managementPageError} <Button onClick={refresh}>Retry</Button></div>
      ) : flights.length === 0 ? (
        <Card>
          <CardContent className="flex items-center justify-center h-64">
            <div className="text-center">
              <div className="text-muted-foreground mb-2">No flights found</div>
              {total === 0 && <>
                <p className="text-sm text-muted-foreground">Start by adding your first flight</p>
                <Button onClick={handleAddNewFlight} className="mt-4"><Plus className="h-4 w-4 mr-2" />Add Your First Flight</Button>
              </>}
            </div>
          </CardContent>
        </Card>
      ) : (
        <div className="space-y-4">
          {flights.map(flight => <FlightCard key={flight.id} flight={flight}
            disabled={busy} onEdit={handleEditFlight} onDelete={handleDeleteFlight} onView={handleViewFlight} />)}
        </div>
      )}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p aria-live="polite">{loading || managementPageError ? '' : `${total === 0 ? 0 : page * size + 1}–${Math.min(page * size + flights.length, total)} of ${total} flights`}</p>
        <div className="flex items-center gap-3">
          <Button variant="outline" disabled={busy || page === 0} onClick={() => setQuery(value => ({ ...value, page: value.page - 1 }))}>Previous</Button>
          <span>Page {totalPages === 0 ? 1 : page + 1} of {Math.max(1, totalPages)}</span>
          <Button variant="outline" disabled={busy || !!managementPageError || page + 1 >= totalPages} onClick={() => setQuery(value => ({ ...value, page: value.page + 1 }))}>Next</Button>
        </div>
      </div>
    </div>
  );
};

export default FlightManagement;
