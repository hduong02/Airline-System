export function getPaymentVerificationRequest(params) {
  const razorpayPaymentId = params.get('razorpay_payment_id') || params.get('payment_id');
  const stripePaymentIntentId = params.get('stripe_payment_intent_id') || params.get('payment_intent');
  if (stripePaymentIntentId) return { stripePaymentIntentId };
  if (razorpayPaymentId) return { razorpayPaymentId };
  // A Stripe Checkout Session ID is not a PaymentIntent ID. Its webhook updates the booking.
  return null;
}

export function getPaymentResult(booking, verifiedPayment) {
  if (booking?.status === 'CANCELLED') return 'cancelled';
  const statuses = [booking?.paymentStatus, verifiedPayment?.status];
  if (statuses.includes('REFUNDED')) return 'refunded';
  if (statuses.includes('FAILED')) return 'failed';
  if (statuses.includes('CANCELLED')) return 'payment-cancelled';
  if (['CONFIRMED', 'COMPLETED'].includes(booking?.status) && booking?.paymentStatus === 'SUCCESS') return 'confirmed';
  return 'pending';
}
