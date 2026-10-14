package com.example.signalgenerator.adapters.input.web

import com.example.signalgenerator.adapters.input.web.dto.OrderDeviationSignalRequest
import com.example.signalgenerator.application.service.SignalService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/signals")
class SignalController(private val signalService: SignalService) {

    @PostMapping("/order-deviation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun publish(@Valid @RequestBody request: OrderDeviationSignalRequest) =
        signalService.publish(request.toDomain())
}
