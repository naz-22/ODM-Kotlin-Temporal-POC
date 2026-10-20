package com.example.odm.adapters.input.web

import com.example.odm.application.ports.input.DeviationDecisionUseCase
import com.example.odm.domain.model.Decision
import com.example.odm.domain.model.DeviationType
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// Web-layer slice test for the synchronous decision endpoint that order-service's
// OdmRestClient calls from the getDeviationDecision Activity. Pins the request and
// response field names both services rely on.
@WebMvcTest(DeviationController::class)
class DeviationControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var deviationDecisionUseCase: DeviationDecisionUseCase

    @Test
    fun `decision request from order-service returns the Drools decision`() {
        whenever(deviationDecisionUseCase.decide("order-1", DeviationType.PAYMENT_FAILED, 1, "declined"))
            .thenReturn(Decision.RETRY)

        mockMvc.perform(
            post("/deviations/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"orderId":"order-1","type":"PAYMENT_FAILED","attemptNumber":1,"reason":"declined"}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.decision").value("RETRY"))
    }

    @Test
    fun `invalid decision request is rejected before any decision is made`() {
        mockMvc.perform(
            post("/deviations/decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"orderId":"","type":"PAYMENT_FAILED","attemptNumber":-1,"reason":""}""")
        ).andExpect(status().isBadRequest)

        verify(deviationDecisionUseCase, never()).decide(any(), any(), any(), any())
    }
}
