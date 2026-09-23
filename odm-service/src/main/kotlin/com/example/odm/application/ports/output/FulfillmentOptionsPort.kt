package com.example.odm.application.ports.output

interface FulfillmentOptionsPort {

    // Returns an alternative way to fulfil the missing quantity, or null if there is none.
    fun findAlternative(orderId: String, itemId: String, market: String, quantity: Int): FulfillmentOption?
}
