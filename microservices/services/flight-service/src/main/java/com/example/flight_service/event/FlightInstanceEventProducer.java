package com.example.flight_service.event;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.example.event.FlightInstanceCreatedEvent;

@Service
@RequiredArgsConstructor
public class FlightInstanceEventProducer {

    private final KafkaTemplate<String, FlightInstanceCreatedEvent> kafkaTemplate;

    public void sendFlightInstanceCreated(FlightInstanceCreatedEvent event) {
        kafkaTemplate.send("flight-instance-created", event);
    }
}

