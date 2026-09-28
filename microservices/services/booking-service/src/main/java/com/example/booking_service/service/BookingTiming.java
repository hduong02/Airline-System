package com.example.booking_service.service;

import io.micrometer.core.instrument.Metrics;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/** Bounded phase names only; never attach booking IDs or customer data. */
public final class BookingTiming {
    private BookingTiming() {}

    public static <T> T measure(String phase, Callable<T> action) throws Exception {
        long started = System.nanoTime();
        boolean success = false;
        try {
            T result = action.call();
            success = true;
            return result;
        } finally {
            Metrics.timer("booking.phase", "phase", phase, "outcome", success ? "success" : "failure")
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }
}
