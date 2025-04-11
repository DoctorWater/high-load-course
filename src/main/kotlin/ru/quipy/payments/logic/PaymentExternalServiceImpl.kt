package ru.quipy.payments.logic

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class PaymentExternalSystemAdapterImpl(
    private val properties: PaymentAccountProperties,
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>
) : PaymentExternalSystemAdapter {

    companion object {
        val mapper = ObjectMapper().registerKotlinModule()
    }

    private val timeout = properties.averageProcessingTime.toMillis() * 2
    private val serviceName = properties.serviceName
    private val accountName = properties.accountName
    private val requestAvgTime = properties.averageProcessingTime
    private val maxRequestsPerSec = properties.rateLimitPerSec
    private val maxConcurrentRequests = properties.parallelRequests
    private val maxRetryCount = 2
    private val requestUtils : RequestUtils = RequestUtils(paymentESService, maxConcurrentRequests, maxRetryCount, maxRequestsPerSec, requestAvgTime,serviceName, accountName)
    

    private val executorService = Executors.newVirtualThreadPerTaskExecutor()

    override fun performPaymentAsync(paymentId: UUID, amount: Int, paymentStartedAt: Long, deadline: Long) {
        val transactionId = UUID.randomUUID()

        paymentESService.update(paymentId) {
            it.logSubmission(success = true, transactionId, now(), Duration.ofMillis(now() - paymentStartedAt))
        }

        executorService.submit {
            requestUtils.tryProcessPayment(paymentId, transactionId, amount, deadline, retryCount = 0, timeout)
                .exceptionally { ex ->
                    requestUtils.cancelRequest(paymentId, transactionId, ex.message ?: "Unknown error")
                    null
                }
            Unit
        }
    }
    
    override fun price() = properties.price
    override fun isEnabled() = properties.enabled
    override fun name() = properties.accountName
}

fun now() = System.currentTimeMillis()