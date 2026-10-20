package com.example.odm.adapters.input.web

import com.example.odm.application.ports.input.FullServeDeviationUseCase
import com.example.odm.application.ports.input.IntakeOutcome
import com.example.odm.application.ports.input.IntakeResult
import com.example.odm.application.ports.input.UpdateOutcome
import com.example.odm.application.ports.input.UpdateResult
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// Web-layer slice test for the Full-Serve demo/support REST ingress: no DB, Temporal or Solace.
// Pins the documented mapping from ingress outcome to HTTP status (README and
// docs/odm-fullserve-walkthrough.md); the outcomes themselves are covered end to end in
// FullServeDeviationEndToEndTest.
@WebMvcTest(FullServeDeviationController::class)
class FullServeDeviationControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var fullServe: FullServeDeviationUseCase

    private fun postSignal() = mockMvc.perform(
        post("/fullserve/deviations/signals").contentType(MediaType.APPLICATION_JSON).content("""{"orderId":"ORD-1"}""")
    )

    private fun postUpdate() = mockMvc.perform(
        post("/fullserve/deviations/updates").contentType(MediaType.APPLICATION_JSON).content("""{"orderId":"ORD-1"}""")
    )

    @Test
    fun `signal outcomes map to 202, 200, 400 and 409`() {
        val expected = mapOf(
            IntakeOutcome.ACCEPTED to 202,
            IntakeOutcome.IGNORED_DUPLICATE to 200,
            IntakeOutcome.REJECTED_INVALID to 400,
            IntakeOutcome.RETRY_LATER to 409
        )
        expected.forEach { (outcome, httpStatus) ->
            whenever(fullServe.receiveSignal(any())).thenReturn(IntakeResult(outcome, "ORD-1"))
            postSignal().andExpect(status().`is`(httpStatus)).andExpect(jsonPath("$.outcome").value(outcome.name))
        }
    }

    @Test
    fun `update outcomes map to 202, 400, 404 and 409`() {
        val expected = mapOf(
            UpdateOutcome.SIGNALLED to 202,
            UpdateOutcome.REJECTED_INVALID to 400,
            UpdateOutcome.NO_OPEN_DEVIATION to 404,
            UpdateOutcome.STALE_DEVIATION_ID to 409,
            UpdateOutcome.WORKFLOW_NOT_RUNNING to 409
        )
        expected.forEach { (outcome, httpStatus) ->
            whenever(fullServe.receiveUpdate(any())).thenReturn(UpdateResult(outcome, "ORD-1"))
            postUpdate().andExpect(status().`is`(httpStatus)).andExpect(jsonPath("$.outcome").value(outcome.name))
        }
    }

    @Test
    fun `unknown deviation id returns 404`() {
        whenever(fullServe.findDeviation("FSD-missing")).thenReturn(null)

        mockMvc.perform(get("/fullserve/deviations/FSD-missing")).andExpect(status().isNotFound)
    }
}
