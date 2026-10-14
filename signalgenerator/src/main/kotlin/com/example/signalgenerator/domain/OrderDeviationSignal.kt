package com.example.signalgenerator.domain

data class OrderDeviationSignal(
    val orderId: String,
    val source: String,
    val serviceType: String,
    val market: String,
    val deviationType: String,
    val reasonCode: String,
    val occurrence: String,
    val reasonCaused: String
)
