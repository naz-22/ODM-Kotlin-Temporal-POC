package com.example.orderlifecycle.application.ports.output

// Output port toward Solace. Order Service publishes only the one business event
// it truly owns: an order reaching ORDER_CONFIRMED. Notification Service and any
// other interested consumer subscribe to it independently.
interface OrderEventPublisherPort {

    fun publishOrderConfirmed(orderId: String, customerId: String)
}
