package com.example.signalgenerator.application.service

import com.example.signalgenerator.application.ports.output.SignalPublisherPort
import com.example.signalgenerator.domain.OrderDeviationSignal
import org.springframework.stereotype.Service

@Service
class SignalService(private val publisher: SignalPublisherPort) {

    fun publish(signal: OrderDeviationSignal) = publisher.publishOrderDeviation(signal)
}
