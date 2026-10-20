package com.example.orderlifecycle.adapters.input.temporal

import com.example.orderlifecycle.application.ports.input.OrderLifecycleWorkflow
import com.example.orderlifecycle.application.ports.output.DeviationDecision
import com.example.orderlifecycle.application.ports.output.InventoryReservationResult
import com.example.orderlifecycle.application.ports.output.OrderLifecycleActivities
import com.example.orderlifecycle.application.ports.output.PaymentAttemptResult
import com.example.orderlifecycle.configuration.kotlinAwareTemporalDataConverter
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.Worker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

// Uses Temporal's own testing support (TestWorkflowEnvironment) rather than mocking
// the workflow itself: the real OrderLifecycleWorkflowImpl orchestration logic runs
// against an in-memory Temporal test server. Only the Activities - the part that
// talks to other services - are mocked, exactly the boundary the hexagonal design
// draws between "deterministic orchestration" and "real work".
class OrderLifecycleWorkflowImplTest {

    private val taskQueue = "order-lifecycle-task-queue-test"

    private lateinit var testEnv: TestWorkflowEnvironment
    private lateinit var worker: Worker
    private lateinit var workflowClient: WorkflowClient
    private lateinit var activities: OrderLifecycleActivities

    @BeforeEach
    fun setUp() {
        testEnv = TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkflowClientOptions(
                    WorkflowClientOptions.newBuilder()
                        .setDataConverter(kotlinAwareTemporalDataConverter)
                        .build()
                )
                .build()
        )
        worker = testEnv.newWorker(taskQueue)
        worker.registerWorkflowImplementationTypes(OrderLifecycleWorkflowImpl::class.java)

        activities = mock()
        // Registered as a hand-written forwarding object rather than the raw Mockito
        // mock (and rather than Kotlin's "by" delegation, which turns out to copy the
        // interface's @ActivityMethod annotations onto its generated methods too):
        // Temporal's activity registration rejects any implementation class whose own
        // methods carry @ActivityMethod ("can be used only on the interface method it
        // implements"). These explicit overrides carry no annotations of their own,
        // while still forwarding every call to the mock for stubbing/verification.
        worker.registerActivitiesImplementations(object : OrderLifecycleActivities {
            override fun validateOrder(orderId: String) = activities.validateOrder(orderId)
            override fun reserveInventory(orderId: String) = activities.reserveInventory(orderId)
            override fun processPayment(orderId: String, attemptNumber: Int) =
                activities.processPayment(orderId, attemptNumber)
            override fun getDeviationDecision(
                orderId: String,
                deviationType: String,
                attemptNumber: Int,
                reason: String
            ) = activities.getDeviationDecision(orderId, deviationType, attemptNumber, reason)
            override fun releaseInventory(orderId: String) = activities.releaseInventory(orderId)
            override fun confirmOrder(orderId: String) = activities.confirmOrder(orderId)
            override fun completeOrder(orderId: String) = activities.completeOrder(orderId)
            override fun cancelOrder(orderId: String) = activities.cancelOrder(orderId)
            override fun failOrder(orderId: String) = activities.failOrder(orderId)
        })

        workflowClient = testEnv.workflowClient
        testEnv.start()
    }

    @AfterEach
    fun tearDown() {
        testEnv.close()
    }

    private fun newWorkflowStub(): OrderLifecycleWorkflow =
        workflowClient.newWorkflowStub(
            OrderLifecycleWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(taskQueue).build()
        )

    @Test
    fun `happy path reaches COMPLETED`() {
        whenever(activities.reserveInventory(any())).thenReturn(InventoryReservationResult.RESERVED)
        whenever(activities.processPayment(any(), eq(1))).thenReturn(PaymentAttemptResult(true, null))

        val result = newWorkflowStub().startOrder("order-happy")

        assertEquals("COMPLETED", result)
        verify(activities).confirmOrder("order-happy")
        verify(activities).completeOrder("order-happy")
    }

    @Test
    fun `inventory unavailable fails the order without touching payment`() {
        whenever(activities.reserveInventory(any())).thenReturn(InventoryReservationResult.UNAVAILABLE)
        whenever(activities.getDeviationDecision(any(), eq("INVENTORY_UNAVAILABLE"), any(), any()))
            .thenReturn(DeviationDecision.FAIL_ORDER)

        val result = newWorkflowStub().startOrder("order-no-stock")

        assertEquals("ORDER_FAILED", result)
        verify(activities).failOrder("order-no-stock")
        verify(activities, org.mockito.kotlin.never()).processPayment(any(), any())
    }

    @Test
    fun `payment business retry succeeds on second attempt`() {
        whenever(activities.reserveInventory(any())).thenReturn(InventoryReservationResult.RESERVED)
        whenever(activities.processPayment(any(), eq(1)))
            .thenReturn(PaymentAttemptResult(false, "declined"))
        whenever(activities.processPayment(any(), eq(2)))
            .thenReturn(PaymentAttemptResult(true, null))
        whenever(activities.getDeviationDecision(any(), eq("PAYMENT_FAILED"), eq(1), any()))
            .thenReturn(DeviationDecision.RETRY)

        val result = newWorkflowStub().startOrder("order-retry-once")

        assertEquals("COMPLETED", result)
        verify(activities).processPayment("order-retry-once", 1)
        verify(activities).processPayment("order-retry-once", 2)
    }

    @Test
    fun `payment retry exhaustion compensates and cancels the order`() {
        whenever(activities.reserveInventory(any())).thenReturn(InventoryReservationResult.RESERVED)
        whenever(activities.processPayment(any(), any()))
            .thenReturn(PaymentAttemptResult(false, "declined"))
        whenever(activities.getDeviationDecision(any(), eq("PAYMENT_FAILED"), eq(1), any()))
            .thenReturn(DeviationDecision.RETRY)
        whenever(activities.getDeviationDecision(any(), eq("PAYMENT_FAILED"), eq(2), any()))
            .thenReturn(DeviationDecision.RETRY)
        whenever(activities.getDeviationDecision(any(), eq("PAYMENT_FAILED"), eq(3), any()))
            .thenReturn(DeviationDecision.COMPENSATE)

        val result = newWorkflowStub().startOrder("order-always-fail")

        assertEquals("ORDER_CANCELLED", result)
        verify(activities).releaseInventory("order-always-fail")
        verify(activities).cancelOrder("order-always-fail")
    }

    @Test
    fun `FAIL_ORDER decision after a payment failure releases inventory and fails the order`() {
        whenever(activities.reserveInventory(any())).thenReturn(InventoryReservationResult.RESERVED)
        whenever(activities.processPayment(any(), eq(1)))
            .thenReturn(PaymentAttemptResult(false, "declined"))
        whenever(activities.getDeviationDecision(any(), eq("PAYMENT_FAILED"), eq(1), any()))
            .thenReturn(DeviationDecision.FAIL_ORDER)

        val result = newWorkflowStub().startOrder("order-fail-decision")

        assertEquals("ORDER_FAILED", result)
        verify(activities).releaseInventory("order-fail-decision")
        verify(activities).failOrder("order-fail-decision")
        verify(activities, org.mockito.kotlin.never()).cancelOrder(any())
    }

    @Test
    fun `ODM returning RETRY forever stops at the safety cap and cancels the order`() {
        whenever(activities.reserveInventory(any())).thenReturn(InventoryReservationResult.RESERVED)
        whenever(activities.processPayment(any(), any()))
            .thenReturn(PaymentAttemptResult(false, "declined"))
        whenever(activities.getDeviationDecision(any(), eq("PAYMENT_FAILED"), any(), any()))
            .thenReturn(DeviationDecision.RETRY)

        val result = newWorkflowStub().startOrder("order-retry-forever")

        assertEquals("ORDER_CANCELLED", result)
        verify(activities, org.mockito.kotlin.times(10)).processPayment(eq("order-retry-forever"), any())
        verify(activities).releaseInventory("order-retry-forever")
        verify(activities).cancelOrder("order-retry-forever")
    }
}
