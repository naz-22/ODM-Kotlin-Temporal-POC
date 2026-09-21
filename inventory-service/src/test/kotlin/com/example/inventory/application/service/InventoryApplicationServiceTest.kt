package com.example.inventory.application.service

import com.example.inventory.application.ports.input.ReservationOutcome
import com.example.inventory.application.ports.output.InventoryEventPublisherPort
import com.example.inventory.application.ports.output.InventoryItemRepository
import com.example.inventory.application.ports.output.ReservationRepository
import com.example.inventory.domain.model.InventoryItem
import com.example.inventory.domain.model.Reservation
import com.example.inventory.domain.model.ReservationStatus
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import kotlin.test.assertEquals

class InventoryApplicationServiceTest {

    private val itemRepository: InventoryItemRepository = mock()
    private val reservationRepository: ReservationRepository = mock()
    private val eventPublisher: InventoryEventPublisherPort = mock()

    private val service = InventoryApplicationService(itemRepository, reservationRepository, eventPublisher)

    @Test
    fun `reserve succeeds and publishes InventoryReserved when stock is sufficient`() {
        whenever(reservationRepository.findByOrderId("order-1")).thenReturn(null)
        whenever(itemRepository.findByProductId("PROD-1")).thenReturn(InventoryItem("PROD-1", 100))

        val outcome = service.reserve("order-1", "PROD-1", 5)

        assertEquals(ReservationOutcome.RESERVED, outcome)
        verify(eventPublisher).publishInventoryReserved("order-1", "PROD-1", 5)
        verify(eventPublisher, never()).publishInventoryUnavailable(any(), any(), any())
    }

    @Test
    fun `reserve returns UNAVAILABLE and publishes InventoryUnavailable when stock is insufficient`() {
        whenever(reservationRepository.findByOrderId("order-2")).thenReturn(null)
        whenever(itemRepository.findByProductId("PROD-OUT-OF-STOCK")).thenReturn(InventoryItem("PROD-OUT-OF-STOCK", 0))

        val outcome = service.reserve("order-2", "PROD-OUT-OF-STOCK", 1)

        assertEquals(ReservationOutcome.UNAVAILABLE, outcome)
        verify(eventPublisher).publishInventoryUnavailable("order-2", "PROD-OUT-OF-STOCK", 1)
    }

    @Test
    fun `reserve is idempotent for a retried orderId`() {
        whenever(reservationRepository.findByOrderId("order-3"))
            .thenReturn(Reservation("order-3", "PROD-1", 5, ReservationStatus.RESERVED, Instant.now()))

        val outcome = service.reserve("order-3", "PROD-1", 5)

        assertEquals(ReservationOutcome.RESERVED, outcome)
        verify(itemRepository, never()).findByProductId(any())
    }

    @Test
    fun `release restores stock and is idempotent when called twice`() {
        val reservation = Reservation("order-4", "PROD-1", 5, ReservationStatus.RESERVED, Instant.now())
        whenever(reservationRepository.findByOrderId("order-4")).thenReturn(reservation)
        whenever(itemRepository.findByProductId("PROD-1")).thenReturn(InventoryItem("PROD-1", 95))

        service.release("order-4")

        verify(eventPublisher).publishInventoryReleased("order-4", "PROD-1", 5)
    }

    @Test
    fun `release is a no-op when no reservation exists`() {
        whenever(reservationRepository.findByOrderId("order-5")).thenReturn(null)

        service.release("order-5")

        verify(eventPublisher, never()).publishInventoryReleased(any(), any(), any())
    }
}
