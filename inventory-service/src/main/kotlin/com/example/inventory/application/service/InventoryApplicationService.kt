package com.example.inventory.application.service

import com.example.inventory.application.ports.input.ReservationOutcome
import com.example.inventory.application.ports.input.ReserveInventoryUseCase
import com.example.inventory.application.ports.output.InventoryEventPublisherPort
import com.example.inventory.application.ports.output.InventoryItemRepository
import com.example.inventory.application.ports.output.ReservationRepository
import com.example.inventory.domain.model.Reservation
import com.example.inventory.domain.model.ReservationStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class InventoryApplicationService(
    private val inventoryItemRepository: InventoryItemRepository,
    private val reservationRepository: ReservationRepository,
    private val eventPublisher: InventoryEventPublisherPort
) : ReserveInventoryUseCase {

    private val log = LoggerFactory.getLogger(InventoryApplicationService::class.java)

    @Transactional
    override fun reserve(orderId: String, productId: String, quantity: Int): ReservationOutcome {
        // orderId is the idempotency key: a reservation row only ever exists here
        // if a previous call already reserved stock for it (we never persist a row
        // for an UNAVAILABLE outcome), so its presence alone answers the retry.
        reservationRepository.findByOrderId(orderId)?.let {
            log.info("Reservation already exists for orderId={}, returning idempotent result", orderId)
            return ReservationOutcome.RESERVED
        }

        val item = inventoryItemRepository.findByProductId(productId)
            ?: throw IllegalArgumentException("Unknown product: $productId")

        if (!item.canFulfil(quantity)) {
            log.info("Inventory unavailable: orderId={}, productId={}, requested={}, available={}",
                orderId, productId, quantity, item.availableStock)
            eventPublisher.publishInventoryUnavailable(orderId, productId, quantity)
            return ReservationOutcome.UNAVAILABLE
        }

        inventoryItemRepository.save(item.reserve(quantity))
        reservationRepository.save(
            Reservation(orderId, productId, quantity, ReservationStatus.RESERVED, Instant.now())
        )
        eventPublisher.publishInventoryReserved(orderId, productId, quantity)
        log.info("Inventory reserved: orderId={}, productId={}, quantity={}", orderId, productId, quantity)

        return ReservationOutcome.RESERVED
    }

    @Transactional
    override fun release(orderId: String) {
        val reservation = reservationRepository.findByOrderId(orderId) ?: run {
            log.info("Release called for orderId={} with no reservation - idempotent no-op", orderId)
            return
        }

        if (reservation.status == ReservationStatus.RELEASED) {
            log.info("Release called for orderId={} which is already released - idempotent no-op", orderId)
            return
        }

        val item = inventoryItemRepository.findByProductId(reservation.productId)
            ?: throw IllegalStateException("Product missing for existing reservation: ${reservation.productId}")

        inventoryItemRepository.save(item.release(reservation.quantity))
        reservationRepository.save(reservation.released())
        eventPublisher.publishInventoryReleased(orderId, reservation.productId, reservation.quantity)
        log.info("Inventory released: orderId={}, productId={}, quantity={}",
            orderId, reservation.productId, reservation.quantity)
    }
}
