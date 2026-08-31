import React from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';

const PaymentPage = ({ cancelled = false }) => {
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const candidate = location.state?.booking?.id || searchParams.get('bookingId');
  const bookingId = /^[1-9]\d*$/.test(String(candidate)) ? String(candidate) : null;

  return (
    <div className="min-h-screen bg-background flex items-center justify-center p-4">
      <Card className="max-w-md w-full">
        <CardContent className="pt-6 space-y-4 text-center">
          <h1 className="text-2xl font-bold">{cancelled ? 'Checkout cancelled' : 'Check your payment status'}</h1>
          <p>{cancelled
            ? 'You left checkout. This does not confirm payment or cancel your booking. Check your booking status before taking further action.'
            : 'Payments are completed through the secure checkout opened when you create a booking. Check your bookings for the current payment status.'}</p>
          {bookingId && <Button onClick={() => navigate(`/booking-success/${bookingId}`)}>Check booking status</Button>}
          <Button variant="outline" onClick={() => navigate('/bookings')}>Back to Bookings</Button>
        </CardContent>
      </Card>
    </div>
  );
};

export default PaymentPage;
