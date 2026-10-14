package com.example.signalgenerator.domain

data class OrderDeviationSignal(
    val deviationKey: String,
    val orderId: String,
    val type: String,
    val releaseStage: String,
    val market: String,
    val itemId: String,
    val requestedQuantity: Int,
    val pickedQuantity: Int,
    val reason: String
)
