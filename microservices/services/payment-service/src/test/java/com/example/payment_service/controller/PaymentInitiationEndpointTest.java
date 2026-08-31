package com.example.payment_service.controller;

import com.example.enums.PaymentGateway;
import com.example.payload.response.PaymentInitiateResponse;
import com.example.payment_service.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PaymentInitiationEndpointTest {
    private final PaymentService service = mock(PaymentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PaymentController(service),
            new InternalPaymentController(service)).build();

    private final String request = """
            {"userId":2,"bookingId":1,"amount":100,"gateway":"STRIPE"}
            """;

    @Test
    void publicInitiationIsNotAvailable() throws Exception {
        mvc.perform(post("/api/payments/initiate").header("X-User-Id", "2")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @Test
    void internalInitiationUsesValidatedRequest() throws Exception {
        when(service.initiatePayment(any())).thenReturn(PaymentInitiateResponse.builder()
                .paymentId(9L).gateway(PaymentGateway.STRIPE).checkoutUrl("https://checkout.stripe.com/test")
                .success(true).build());

        mvc.perform(post("/internal/payments/initiate")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(9));
        verify(service).initiatePayment(argThat(payment -> payment.getBookingId() == 1L
                && payment.getUserId() == 2L && payment.getAmount() == 100.0));
    }

    @Test
    void internalInitiationRejectsInvalidAmount() throws Exception {
        mvc.perform(post("/internal/payments/initiate")
                        .contentType(MediaType.APPLICATION_JSON).content(request.replace("100", "-1")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
