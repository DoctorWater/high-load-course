package ru.quipy.payments.logic

import com.fasterxml.jackson.module.kotlin.readValue
import ru.quipy.common.utils.OngoingWindow
import ru.quipy.common.utils.SlidingWindowRateLimiter
import ru.quipy.core.EventSourcingService
import ru.quipy.payments.api.PaymentAggregate
import ru.quipy.payments.logic.PaymentExternalSystemAdapterImpl.Companion.mapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture

class RequestUtils(
    private val paymentESService: EventSourcingService<UUID, PaymentAggregate, PaymentAggregateState>,
    private val maxConcurrentRequests: Int,
    private val maxRetryCount: Int,
    private val maxRequestsPerSec: Int,
    private val requestAvgTime: Duration,
    private val serviceName: String,
    private val accountName: String,
) {

    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val window = OngoingWindow(maxConcurrentRequests)
    private val rateLimiter = SlidingWindowRateLimiter(maxRequestsPerSec.toLong(), Duration.ofSeconds(1))

    fun tryProcessPayment(
        paymentId: UUID,
        transactionId: UUID,
        amount: Int,
        deadline: Long,
        retryCount: Int,
        timeout: Long
    ): CompletableFuture<Void> {

        if (!isPaymentValid(paymentId, transactionId, deadline))
            return CompletableFuture.completedFuture(null)

        val url = "http://localhost:1234/external/process?serviceName=$serviceName&accountName=$accountName" +
                "&transactionId=$transactionId&paymentId=$paymentId&amount=$amount&timeout=$timeout"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(timeout))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply { response ->
                try {
                    mapper.readValue<ExternalSysResponse>(response.body())
                } catch (e: Exception) {
                    ExternalSysResponse(transactionId.toString(), paymentId.toString(), false, e.message)
                }
            }
            .thenCompose { externalResponse ->

                paymentESService.update(paymentId) {
                    it.logProcessing(externalResponse.result, now(), transactionId, reason = externalResponse.message)
                }

                if (externalResponse.result) {
                    CompletableFuture.completedFuture(null)
                } else {
                    if (retryCount < maxRetryCount) {
                        tryProcessPayment(paymentId, transactionId, amount, deadline, retryCount + 1, timeout)
                    } else {
                        CompletableFuture.completedFuture(null)
                    }
                }
            }
            .whenComplete { _, _ ->
                window.release()
            }
    }

    private fun isPaymentValid(
        paymentId: UUID,
        transactionId: UUID,
        deadline: Long
    ): Boolean {
        if (isCancelBecauseOfDeadline(deadline)) {
            cancelRequest(paymentId, transactionId, "Will be completed after deadline")
            window.release()
            return false
        }

        if (!rateLimiter.tick()) {
            cancelRequest(paymentId, transactionId, "Rate limit exceeded")
            window.release()
            return false
        }

        if (!window.tryAcquire()) {
            cancelRequest(paymentId, transactionId, "Too many concurrent requests.")
            window.release()
            return false
        }
        return true
    }

    private fun isCancelBecauseOfDeadline(deadline: Long): Boolean {
        return (now() + requestAvgTime.toMillis() * 1.5) >= deadline
    }

    fun cancelRequest(paymentId: UUID, transactionId: UUID, reason: String) {
        paymentESService.update(paymentId) {
            it.logProcessing(false, now(), transactionId, reason = reason)
        }
    }

}
