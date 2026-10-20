package com.example.orderlifecycle.adapters.input.web

import com.example.orderlifecycle.application.ports.input.PlaceOrderUseCase
import com.example.orderlifecycle.domain.model.Order
import com.example.orderlifecycle.domain.model.OrderStatus
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// Web-layer slice test: no DB, no Temporal, no Solace - just the REST contract.
// The workflow is covered by OrderLifecycleWorkflowImplTest on Temporal's in-memory
// test server; only the live demo scenarios in the README need the docker-compose stack.
@WebMvcTest(OrderController::class)
class OrderControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var placeOrderUseCase: PlaceOrderUseCase

    @Test
    fun `POST orders creates an order and starts its lifecycle`() {
        val order = Order(
            id = UUID.randomUUID(),
            customerId = "customer-1",
            productId = "PROD-1",
            quantity = 1,
            amount = BigDecimal("100.00"),
            status = OrderStatus.ORDER_PLACED,
            createdAt = Instant.now()
        )
        whenever(placeOrderUseCase.placeOrder(any())).thenReturn(order)

        mockMvc.perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"customerId":"customer-1","productId":"PROD-1","quantity":1,"amount":100.00}"""
                )
        ).andExpect(status().isOk)
    }

    @Test
    fun `POST orders rejects an invalid request`() {
        mockMvc.perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"customerId":"","productId":"PROD-1","quantity":0,"amount":0}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `GET orders by id returns 404 when the order does not exist`() {
        whenever(placeOrderUseCase.findOrderById(any())).thenReturn(null)

        mockMvc.perform(get("/orders/${UUID.randomUUID()}"))
            .andExpect(status().isNotFound)
    }
}
