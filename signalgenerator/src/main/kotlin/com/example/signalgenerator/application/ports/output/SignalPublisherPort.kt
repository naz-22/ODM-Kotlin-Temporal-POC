package com.example.signalgenerator.application.ports.output

import com.example.signalgenerator.domain.OrderDeviationSignal

interface SignalPublisherPort {

    fun publishOrderDeviation(signal: OrderDeviationSignal)
}
