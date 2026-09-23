package com.example.odm.adapters.input.temporal

import com.example.odm.application.ports.input.AutorecoveryInput
import com.example.odm.application.ports.input.AutorecoveryWorkflow
import com.example.odm.application.ports.input.ManualResolution
import com.example.odm.application.ports.output.AutorecoveryActivities
import com.example.odm.application.ports.output.FulfillmentOption
import com.example.odm.application.ports.output.RebookResult
import com.example.odm.configuration.kotlinAwareTemporalDataConverter
import com.example.odm.domain.model.RecoveryDecision
import com.example.odm.domain.model.RecoveryResolution
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

// The real AutorecoveryWorkflowImpl runs on Temporal's in-memory test server; only the
// Activities are mocked (the same boundary OrderLifecycleWorkflowImplTest draws).
class AutorecoveryWorkflowImplTest {

    private lateinit var testEnv: TestWorkflowEnvironment
    private lateinit var client: WorkflowClient
    private lateinit var activities: AutorecoveryActivities

    private val option = FulfillmentOption("FO-ITEM-1-ALT", "US-ALT-NODE")

    @BeforeEach
    fun setUp() {
        testEnv = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder().setDataConverter(kotlinAwareTemporalDataConverter).build()
                )
                .build()
        )
        val worker = testEnv.newWorker(AutorecoveryWorkflow.TASK_QUEUE)
        worker.registerWorkflowImplementationTypes(AutorecoveryWorkflowImpl::class.java)

        activities = mock()
        // Forwarding object without @ActivityMethod annotations (see OrderLifecycleWorkflowImplTest).
        worker.registerActivitiesImplementations(object : AutorecoveryActivities {
            override fun startAutoRecovery(deviationKey: String) = activities.startAutoRecovery(deviationKey)
            override fun findFulfillmentOption(deviationKey: String) = activities.findFulfillmentOption(deviationKey)
            override fun rebookOrder(deviationKey: String, optionId: String) = activities.rebookOrder(deviationKey, optionId)
            override fun requestManualResolution(deviationKey: String, reason: String) =
                activities.requestManualResolution(deviationKey, reason)
            override fun closeRecovery(deviationKey: String, resolution: RecoveryResolution, note: String) =
                activities.closeRecovery(deviationKey, resolution, note)
        })

        client = testEnv.workflowClient
        testEnv.start()
    }

    @AfterEach
    fun tearDown() = testEnv.close()

    private fun newWorkflow(key: String) = client.newWorkflowStub(
        AutorecoveryWorkflow::class.java,
        WorkflowOptions.newBuilder()
            .setWorkflowId(AutorecoveryWorkflow.workflowIdFor(key))
            .setTaskQueue(AutorecoveryWorkflow.TASK_QUEUE)
            .build()
    )

    // Starts the workflow, proves it is parked waiting (RUNNING, nothing closed), then
    // sends the Signal and returns the workflow result.
    private fun runUntilWaitingThenSignal(key: String, decision: RecoveryDecision): String {
        val workflow = newWorkflow(key)
        WorkflowClient.start(workflow::recover, AutorecoveryInput(key, decision, "test-rule"))

        verify(activities, timeout(5_000)).requestManualResolution(eq(key), any())
        val stub = WorkflowStub.fromTyped(workflow)
        assertEquals(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING, stub.describe().status)
        verify(activities, never()).closeRecovery(any(), any(), any())

        workflow.manualResolution(ManualResolution("rcmp-agent", "handled"))
        return stub.getResult(String::class.java)
    }

    @Test
    fun `short pick auto recovery - option found and rebook succeeds - closes as AUTO_RECOVERED`() {
        whenever(activities.findFulfillmentOption("sp-1")).thenReturn(option)
        whenever(activities.rebookOrder("sp-1", option.optionId)).thenReturn(RebookResult(true, "RB-1", null))

        val result = newWorkflow("sp-1").recover(AutorecoveryInput("sp-1", RecoveryDecision.AUTO_RECOVER, "r"))

        assertEquals("AUTO_RECOVERED", result)
        verify(activities).startAutoRecovery("sp-1")
        verify(activities).closeRecovery(eq("sp-1"), eq(RecoveryResolution.AUTO_RECOVERED), any())
        verify(activities, never()).requestManualResolution(any(), any())
    }

    @Test
    fun `short pick with no alternative falls back to MANUAL and waits for the signal`() {
        whenever(activities.findFulfillmentOption("sp-2")).thenReturn(null)

        val result = runUntilWaitingThenSignal("sp-2", RecoveryDecision.AUTO_RECOVER)

        assertEquals("MANUALLY_RESOLVED", result)
        verify(activities, never()).rebookOrder(any(), any())
        verify(activities).requestManualResolution(eq("sp-2"), eq("Automated recovery failed: no alternative fulfillment option available"))
        verify(activities).closeRecovery(eq("sp-2"), eq(RecoveryResolution.MANUALLY_RESOLVED), eq("Resolved by rcmp-agent: handled"))
    }

    @Test
    fun `short pick with rebook failure falls back to MANUAL and waits for the signal`() {
        whenever(activities.findFulfillmentOption("sp-3")).thenReturn(option)
        whenever(activities.rebookOrder("sp-3", option.optionId)).thenReturn(RebookResult(false, null, "rejected"))

        val result = runUntilWaitingThenSignal("sp-3", RecoveryDecision.AUTO_RECOVER)

        assertEquals("MANUALLY_RESOLVED", result)
        verify(activities, never()).closeRecovery(any(), eq(RecoveryResolution.AUTO_RECOVERED), any())
        verify(activities).closeRecovery(eq("sp-3"), eq(RecoveryResolution.MANUALLY_RESOLVED), any())
    }

    @Test
    fun `post-release MANUAL never calls Fulfillment Options or Rebook, and the signal wakes the workflow`() {
        val result = runUntilWaitingThenSignal("psf-1", RecoveryDecision.MANUAL)

        assertEquals("MANUALLY_RESOLVED", result)
        verify(activities, never()).startAutoRecovery(any())
        verify(activities, never()).findFulfillmentOption(any())
        verify(activities, never()).rebookOrder(any(), any())
        verify(activities).closeRecovery(eq("psf-1"), eq(RecoveryResolution.MANUALLY_RESOLVED), any())
    }

    @Test
    fun `a restart handed to RCMP uses the manualReason from the input`() {
        val key = "restart-1"
        val workflow = newWorkflow(key)
        WorkflowClient.start(
            workflow::recover,
            AutorecoveryInput(key, RecoveryDecision.MANUAL, "r", manualReason = "handover after failed run")
        )
        verify(activities, timeout(5_000)).requestManualResolution(key, "handover after failed run")
        workflow.manualResolution(ManualResolution("rcmp-agent", "ok"))

        assertEquals("MANUALLY_RESOLVED", WorkflowStub.fromTyped(workflow).getResult(String::class.java))
        verify(activities, never()).findFulfillmentOption(any())
    }
}
