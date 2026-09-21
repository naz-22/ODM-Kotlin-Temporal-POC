package com.example.notification.adapters.input.web

import com.example.notification.domain.NotificationStore
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Demo-only visibility into what the Solace consumer has processed - not part of
// the required architecture, just makes "notification processed" observable without
// having to read service logs during a demo.
@RestController
@RequestMapping("/notifications")
class NotificationController(
    private val notificationStore: NotificationStore
) {

    @GetMapping
    fun all() = notificationStore.all()

    @GetMapping("/{orderId}")
    fun byOrderId(@PathVariable orderId: String): ResponseEntity<Any> {
        val notification = notificationStore.findByOrderId(orderId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(notification)
    }
}
