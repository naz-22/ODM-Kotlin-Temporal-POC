package com.example.inventory.application.ports.output

import com.example.inventory.domain.model.Reservation

interface ReservationRepository {

    fun findByOrderId(orderId: String): Reservation?

    fun save(reservation: Reservation): Reservation
}
