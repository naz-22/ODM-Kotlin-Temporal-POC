package com.example.odm.application.ports.output

interface RebookPort {

    fun rebook(orderId: String, itemId: String, optionId: String, quantity: Int): RebookResult
}
