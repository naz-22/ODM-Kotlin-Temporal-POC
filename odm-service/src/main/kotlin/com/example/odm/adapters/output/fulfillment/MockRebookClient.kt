package com.example.odm.adapters.output.fulfillment

import com.example.odm.application.ports.output.RebookPort
import com.example.odm.application.ports.output.RebookResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// Deterministic in-process stand-in for a Rebook API (POC only).
// Rebooking succeeds for every item, except REBOOK_FAILURE_ITEM.
@Component
class MockRebookClient : RebookPort {

    private val log = LoggerFactory.getLogger(MockRebookClient::class.java)

    override fun rebook(orderId: String, itemId: String, optionId: String, quantity: Int): RebookResult {
        val result = if (itemId == REBOOK_FAILURE_ITEM) {
            RebookResult(succeeded = false, rebookReference = null, failureReason = "Rebook rejected for $itemId")
        } else {
            RebookResult(succeeded = true, rebookReference = "RB-$orderId-$optionId", failureReason = null)
        }
        log.info("[mock Rebook] order={} item={} option={} qty={} -> {}", orderId, itemId, optionId, quantity, result)
        return result
    }

    companion object {
        const val REBOOK_FAILURE_ITEM = "ITEM-REBOOK-FAIL"
    }
}
