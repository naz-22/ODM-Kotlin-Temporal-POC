package com.example.inventory.adapters.output.persistence

import com.example.inventory.application.ports.output.InventoryItemRepository
import com.example.inventory.application.ports.output.ReservationRepository
import com.example.inventory.domain.model.InventoryItem
import com.example.inventory.domain.model.Reservation
import org.springframework.stereotype.Component

@Component
class InventoryItemPersistenceAdapter(
    private val jpaRepository: SpringDataInventoryItemRepository
) : InventoryItemRepository {

    override fun findByProductId(productId: String): InventoryItem? =
        jpaRepository.findById(productId).map { InventoryItem(it.productId, it.availableStock) }.orElse(null)

    override fun save(item: InventoryItem): InventoryItem {
        jpaRepository.save(InventoryItemEntity(item.productId, item.availableStock))
        return item
    }
}

@Component
class ReservationPersistenceAdapter(
    private val jpaRepository: SpringDataReservationRepository
) : ReservationRepository {

    override fun findByOrderId(orderId: String): Reservation? =
        jpaRepository.findById(orderId).map {
            Reservation(it.orderId, it.productId, it.quantity, it.status, it.createdAt)
        }.orElse(null)

    override fun save(reservation: Reservation): Reservation {
        jpaRepository.save(
            ReservationEntity(
                reservation.orderId, reservation.productId, reservation.quantity,
                reservation.status, reservation.createdAt
            )
        )
        return reservation
    }
}
