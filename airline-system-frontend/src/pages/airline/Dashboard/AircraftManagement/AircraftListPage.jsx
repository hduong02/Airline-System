import React, { useState } from 'react';
import { useDispatch, useSelector } from 'react-redux';
import { deleteAircraft, listAllAircrafts } from '@/Redux/aircraft/aircraftThunks';
import { setCurrentPage } from '@/Redux/aircraft/aircraftSlice';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { useNavigate } from 'react-router-dom';
import AircraftTable from '@/components/aircraft/AircraftTable';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Plus, Plane } from 'lucide-react';

const AircraftListPage = () => {
  const navigate = useNavigate();
  const dispatch = useDispatch();
  const aircraftState = useSelector(state => state.aircraft);
  const [deletingAircraft, setDeletingAircraft] = useState(null);
  const [deleting, setDeleting] = useState(false);
  const deletionInFlight = React.useRef(false);
  const [deleteError, setDeleteError] = useState(null);
  const confirmDelete = async () => {
    if (!deletingAircraft || deletionInFlight.current) return;
    deletionInFlight.current = true;
    setDeleting(true);
    setDeleteError(null);
    try {
      await dispatch(deleteAircraft(deletingAircraft.id)).unwrap();
      const { currentPage, pageSize, searchKeyword, statusFilter, sortBy, sortDirection, paginatedAircrafts } = aircraftState;
      const page = paginatedAircrafts.content.length === 1 && currentPage > 0 ? currentPage - 1 : currentPage;
      setDeletingAircraft(null);
      if (page !== currentPage) dispatch(setCurrentPage(page));
      else await dispatch(listAllAircrafts({ page, size: pageSize, search: searchKeyword,
        status: statusFilter !== 'all' ? statusFilter : undefined, sortBy, sortDirection })).unwrap();
    } catch (error) { setDeleteError(String(error)); }
    finally { deletionInFlight.current = false; setDeleting(false); }
  };

  const handleViewDetails = (aircraft) => {
    navigate(`/airline/aircraft/${aircraft.id}`);
  };

  const handleEdit = (aircraft) => {
    navigate(`/airline/aircraft/${aircraft.id}/edit`);
  };

  const handleDelete = (aircraft) => {
    setDeleteError(null);
    setDeletingAircraft(aircraft);
  };

  const handleCreateAircraft = () => {
    navigate('/airline/aircraft/new');
  };

  return (
    <div className="container mx-auto px-4 py-8 space-y-6">
      {/* Page Header */}
      <div className="flex flex-col md:flex-row justify-between items-start md:items-center space-y-4 md:space-y-0">
        <div>
          <h1 className="text-3xl font-bold flex items-center">
            <Plane className="h-8 w-8 mr-3" />
            Aircraft Management
          </h1>
          <p className="text-gray-600 mt-2">
            Manage your airline's fleet of aircraft, cabin configurations, and seat layouts
          </p>
        </div>

        <Button onClick={handleCreateAircraft} size="lg">
          <Plus className="h-5 w-5 mr-2" />
          Add New Aircraft
        </Button>
      </div>

      {/* Quick Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <Card>
          <CardContent className="pt-6">
            <div className="text-center">
              <p className="text-2xl font-bold text-blue-600">24</p>
              <p className="text-sm text-gray-600">Total Aircraft</p>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="pt-6">
            <div className="text-center">
              <p className="text-2xl font-bold text-green-600">18</p>
              <p className="text-sm text-gray-600">Active</p>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="pt-6">
            <div className="text-center">
              <p className="text-2xl font-bold text-yellow-600">4</p>
              <p className="text-sm text-gray-600">Maintenance</p>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="pt-6">
            <div className="text-center">
              <p className="text-2xl font-bold text-purple-600">4,280</p>
              <p className="text-sm text-gray-600">Total Seats</p>
            </div>
          </CardContent>
        </Card>
      </div>

      {deleteError && !deletingAircraft && <p role="alert" className="text-red-600">{deleteError}</p>}
      <Dialog open={Boolean(deletingAircraft)} onOpenChange={open => { if (!open && !deleting) setDeletingAircraft(null); }}>
        <DialogContent>
          <DialogHeader><DialogTitle>Delete aircraft {deletingAircraft?.code}?</DialogTitle></DialogHeader>
          <p>This permanently removes the aircraft from your fleet.</p>
          {deleteError && <p role="alert" className="text-red-600">{deleteError}</p>}
          <Button variant="outline" disabled={deleting} onClick={() => setDeletingAircraft(null)}>Cancel</Button>
          <Button variant="destructive" disabled={deleting} onClick={confirmDelete}>{deleting ? 'Deleting...' : 'Delete aircraft'}</Button>
        </DialogContent>
      </Dialog>
      {/* Aircraft Table */}
      <AircraftTable
        onViewDetails={handleViewDetails}
        onEdit={handleEdit}
        onDelete={handleDelete}
      />
    </div>
  );
};

export default AircraftListPage;