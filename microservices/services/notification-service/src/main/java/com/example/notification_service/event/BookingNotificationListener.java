package com.example.notification_service.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.UnsupportedEncodingException;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.event.BookingConfirmedEvent;
import com.example.notification_service.service.EmailService;

import jakarta.mail.MessagingException;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookingNotificationListener {

    private final EmailService emailService;

    @KafkaListener(
            topics = "booking.confirmed",
            groupId = "notification-service-group"
    )
    @Transactional
    public void handleBookingConfirmed(@Payload BookingConfirmedEvent event)
            throws UnsupportedEncodingException, MessagingException {

        emailService.sendBookingConfirmation(event);
    }
}