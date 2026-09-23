package com.example.odm.adapters.output.fulfillment

import com.example.odm.application.ports.output.FulfillmentOption
import com.example.odm.application.ports.output.FulfillmentOptionsPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// Deterministic in-process stand-in for a Fulfillment Options API (POC only).
// Every item has an alternative, except NO_ALTERNATIVE_ITEM.
@Component
class MockFulfillmentOptionsClient : FulfillmentOptionsPort {

    private val log = LoggerFactory.getLogger(MockFulfillmentOptionsClient::class.java)

    override fun findAlternative(orderId: String, itemId: String, market: String, quantity: Int): FulfillmentOption? {
        val option = if (itemId == NO_ALTERNATIVE_ITEM) null
        else FulfillmentOption(optionId = "FO-$itemId-ALT", fulfilmentNode = "$market-ALT-NODE")
        log.info("[mock Fulfillment Options] order={} item={} market={} qty={} -> {}", orderId, itemId, market, quantity, option)
        return option
    }

    companion object {
        const val NO_ALTERNATIVE_ITEM = "ITEM-NO-ALTERNATIVE"
    }
}
