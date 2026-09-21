package com.example.orderlifecycle.adapters.output.temporal

import com.example.orderlifecycle.application.ports.input.PlaceOrderUseCase
import com.example.orderlifecycle.application.ports.output.DeviationDecision
import com.example.orderlifecycle.application.ports.output.DeviationDecisionClientPort
import com.example.orderlifecycle.application.ports.output.InventoryClientPort
import com.example.orderlifecycle.application.ports.output.InventoryReservationResult
import com.example.orderlifecycle.application.ports.output.OrderEventPublisherPort
import com.example.orderlifecycle.application.ports.output.OrderLifecycleActivities
import com.example.orderlifecycle.application.ports.output.PaymentAttemptResult
import com.example.orderlifecycle.application.ports.output.PaymentClientPort
import com.example.orderlifecycle.domain.model.Order
import com.example.orderlifecycle.domain.model.OrderStatus
import org.slf4j.LoggerFactory
import java.util.UUID

// This class is where the real (non-deterministic) work happens: DB transitions
// through the application layer, and outbound REST/Solace calls through the output
// ports. The Workflow (OrderLifecycleWorkflowImpl) never touches any of this directly.
class OrderLifecycleActivitiesImpl(
    private val orderUseCase: PlaceOrderUseCase,
    private val inventoryClient: InventoryClientPort,
    private val paymentClient: PaymentClientPort,
    private val deviationDecisionClient: DeviationDecisionClientPort,
    private val orderEventPublisher: OrderEventPublisherPort
) : OrderLifecycleActivities {

    private val log = LoggerFactory.getLogger(OrderLifecycleActivitiesImpl::class.java)

    private fun orderOrThrow(orderId: String): Order =
        orderUseCase.findOrderById(UUID.fromString(orderId))
            ?: throw IllegalStateException("Order not found: $orderId")

    override fun validateOrder(orderId: String) {
        val order = orderOrThrow(orderId)
        require(order.quantity > 0) { "Order quantity must be positive: $orderId" }
        require(order.amount.signum() > 0) { "Order amount must be positive: $orderId" }

        orderUseCase.transitionOrder(order.id, OrderStatus.VALIDATING)
        log.info("Order validated: orderId={}", orderId)
    }

    override fun reserveInventory(orderId: String): InventoryReservationResult {
        val order = orderOrThrow(orderId)

        orderUseCase.transitionOrder(order.id, OrderStatus.INVENTORY_RESERVATION_PENDING)

        val result = inventoryClient.reserve(orderId, order.productId, order.quantity)

        if (result == InventoryReservationResult.RESERVED) {
            orderUseCase.transitionOrder(order.id, OrderStatus.INVENTORY_RESERVED)
        }
        // On UNAVAILABLE we deliberately leave the order in INVENTORY_RESERVATION_PENDING;
        // the workflow will ask ODM for a decision next, and failOrder() makes the final move.

        log.info("Inventory reservation result: orderId={}, result={}", orderId, result)
        return result
    }

    override fun processPayment(orderId: String, attemptNumber: Int): PaymentAttemptResult {
        val order = orderOrThrow(orderId)

        if (attemptNumber == 1) {
            orderUseCase.transitionOrder(order.id, OrderStatus.PAYMENT_PENDING)
        }
        // Subsequent business-retry attempts stay in PAYMENT_PENDING; only the
        // status, not a new lifecycle stage, changes between payment attempts.

        val result = paymentClient.charge(orderId, order.customerId, order.amount, attemptNumber)
        log.info(
            "Payment attempt result: orderId={}, attempt={}, succeeded={}",
            orderId, attemptNumber, result.succeeded
        )

        if (result.succeeded) {
            orderUseCase.transitionOrder(order.id, OrderStatus.PAYMENT_CONFIRMED)
        }

        return result
    }

    override fun getDeviationDecision(
        orderId: String,
        deviationType: String,
        attemptNumber: Int,
        reason: String
    ): DeviationDecision {
        val decision = deviationDecisionClient.decide(orderId, deviationType, attemptNumber, reason)
        log.info(
            "ODM/Drools decision: orderId={}, type={}, attempt={}, decision={}",
            orderId, deviationType, attemptNumber, decision
        )
        return decision
    }

    override fun releaseInventory(orderId: String) {
        inventoryClient.release(orderId)
        log.info("Inventory release requested: orderId={}", orderId)
    }

    override fun confirmOrder(orderId: String) {
        val order = orderOrThrow(orderId)
        orderUseCase.transitionOrder(order.id, OrderStatus.ORDER_CONFIRMED)
        orderEventPublisher.publishOrderConfirmed(orderId, order.customerId)
        log.info("Order confirmed: orderId={}", orderId)
    }

    override fun completeOrder(orderId: String) {
        val order = orderOrThrow(orderId)
        orderUseCase.transitionOrder(order.id, OrderStatus.NOTIFICATION_PENDING)
        orderUseCase.transitionOrder(order.id, OrderStatus.COMPLETED)
        log.info("Order completed: orderId={}", orderId)
    }

    override fun cancelOrder(orderId: String) {
        val order = orderOrThrow(orderId)
        orderUseCase.transitionOrder(order.id, OrderStatus.ORDER_CANCELLED)
        log.info("Order cancelled: orderId={}", orderId)
    }

    override fun failOrder(orderId: String) {
        val order = orderOrThrow(orderId)
        orderUseCase.transitionOrder(order.id, OrderStatus.ORDER_FAILED)
        log.info("Order failed: orderId={}", orderId)
    }
}
