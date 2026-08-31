import React from 'react';
import { useNavigate } from 'react-router-dom';
import { Button } from '@/components/ui/button';

// Legacy links contain a PNR, whereas the booking API requires a booking ID.
const ETicket = () => {
  const navigate = useNavigate();
  return (
    <div className="min-h-screen flex items-center justify-center p-4">
      <div className="max-w-md text-center space-y-4">
        <h1 className="text-2xl font-bold">Select a booking to view your ticket</h1>
        <p>Open your booking history and choose View Ticket for the confirmed booking.</p>
        <Button onClick={() => navigate('/bookings')}>Back to Bookings</Button>
      </div>
    </div>
  );
};

export default ETicket;
