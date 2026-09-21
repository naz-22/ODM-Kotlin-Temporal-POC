package com.example.payment.adapters.input.web

import com.example.payment.adapters.input.web.dto.ChargeRequest
import com.example.payment.adapters.input.web.dto.ChargeResponse
import com.example.payment.application.ports.input.ProcessPaymentUseCase
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/payments")
class PaymentController(
    private val processPaymentUseCase: ProcessPaymentUseCase
) {

    // Backs the Temporal "processPayment" Activity. A declined payment is a normal
    // business outcome and is returned with 200, not raised as an HTTP error.
    @PostMapping
    fun charge(@Valid @RequestBody request: ChargeRequest): ChargeResponse {
        val outcome = processPaymentUseCase.charge(
            request.orderId, request.customerId, request.amount, request.attemptNumber
        )
        return ChargeResponse(outcome.succeeded, outcome.failureReason)
    }
}
