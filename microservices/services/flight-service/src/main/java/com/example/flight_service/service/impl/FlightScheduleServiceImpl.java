package com.example.flight_service.service.impl;

import com.example.enums.FlightStatus;
import com.example.flight_service.mapper.FlightScheduleMapper;
import com.example.flight_service.model.Flight;
import com.example.flight_service.model.FlightSchedule;
import com.example.flight_service.repository.FlightRepository;
import com.example.flight_service.repository.FlightScheduleRepository;
import com.example.flight_service.service.FlightInstanceService;
import com.example.flight_service.service.FlightScheduleService;
import com.example.payload.request.FlightInstanceRequest;
import com.example.payload.request.FlightScheduleRequest;
import com.example.payload.response.AirportResponse;
import com.example.payload.response.FlightScheduleResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class FlightScheduleServiceImpl implements FlightScheduleService {

    private final FlightScheduleRepository flightScheduleRepository;
    private final FlightRepository flightRepository;
    private final FlightInstanceService flightInstanceService;

    @Override
    public FlightScheduleResponse createFlightSchedule(Long airlineId,
                FlightScheduleRequest request) throws Exception {
        Flight flight = flightRepository.findById(request.getFlightId())
                .orElseThrow(() -> new Exception(
                        "Flight not found with id: " + request.getFlightId()));

        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new Exception("End date must be after start date");
        }

        FlightSchedule schedule = FlightScheduleMapper.toEntity(request, flight);
        FlightSchedule savedSchedule = flightScheduleRepository.save(schedule);

        List<DayOfWeek> operatingDays = schedule.getOperatingDays();
        LocalDate startDate = schedule.getStartDate();
        LocalDate endDate = schedule.getEndDate();

        FlightInstanceRequest flightInstanceRequest = FlightInstanceRequest.builder()
                .scheduleId(savedSchedule.getId())
                .flightId(flight.getId())
                .arrivalAirportId(flight.getArrivalAirportId())
                .departureAirportId(flight.getDepartureAirportId())
                .status(FlightStatus.SCHEDULED)
                .build();

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            if (operatingDays.contains(date.getDayOfWeek())) {
                flightInstanceRequest.setDepartureDateTime(
                        LocalDateTime.of(date, schedule.getDepartureTime()));

                flightInstanceRequest.setArrivalDateTime(
                        LocalDateTime.of(date, schedule.getArrivalTime()));

                flightInstanceService.createFlightInstance(airlineId, flightInstanceRequest);
            }
        }
        return getFlightScheduleResponse(savedSchedule);
    }

    @Override
    @Transactional(readOnly = true)
    public FlightScheduleResponse getFlightScheduleById(Long id) throws Exception {
        FlightSchedule schedule = flightScheduleRepository.findById(id)
                .orElseThrow(() -> new Exception(
                        "Flight schedule not found with id: " + id));
        return getFlightScheduleResponse(schedule);
    }

    @Override
    public List<FlightScheduleResponse> getFlightScheduleByAirline(Long airlineId) {
        List<FlightSchedule> schedules = flightScheduleRepository
                .findByFlightAirlineId(airlineId);
        return schedules.stream().map(
                schedule -> {
                    try {
                        return getFlightScheduleResponse(schedule);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
        ).collect(Collectors.toList());
    }


    @Override
    public FlightScheduleResponse updateFlightSchedule(Long id,
            FlightScheduleRequest request) throws Exception {
        
        FlightSchedule existingSchedule = flightScheduleRepository.findById(id)
                .orElseThrow(() -> new Exception(
                        "Flight schedule not found with id: " + id));

        FlightScheduleMapper.updateEntity(request, existingSchedule);
        FlightSchedule updated = flightScheduleRepository.save(existingSchedule);
        
        return getFlightScheduleResponse(updated);
    }



    @Override
    public void deleteFlightSchedule(Long id) throws Exception {
        FlightSchedule schedule = flightScheduleRepository.findById(id)
                .orElseThrow(() -> new Exception(
                        "Flight schedule not found with id: " + id));
        flightScheduleRepository.delete(schedule);
    }

    public FlightScheduleResponse getFlightScheduleResponse(
            FlightSchedule schedule) throws Exception {
        
        AirportResponse arrivalAirport = AirportResponse.builder()
                .id(schedule.getArrivalAirportId())
                .build();

        AirportResponse departureAirport = AirportResponse.builder()
                .id(schedule.getDepartureAirportId())
                .build();

        return FlightScheduleMapper.toResponse(schedule, arrivalAirport, departureAirport);
    }
}