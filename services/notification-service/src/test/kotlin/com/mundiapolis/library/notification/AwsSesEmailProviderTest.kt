package com.mundiapolis.library.notification

import aws.sdk.kotlin.services.sesv2.model.SendEmailRequest
import aws.sdk.kotlin.services.sesv2.model.AccountSuspendedException
import aws.sdk.kotlin.services.sesv2.model.LimitExceededException
import aws.sdk.kotlin.services.sesv2.model.MessageRejected
import aws.sdk.kotlin.services.sesv2.model.SendingPausedException
import aws.sdk.kotlin.services.sesv2.model.TooManyRequestsException
import com.mundiapolis.library.notification.adapter.outbound.email.AwsSesEmailProvider
import com.mundiapolis.library.notification.adapter.outbound.email.SesEmailGateway
import com.mundiapolis.library.notification.adapter.outbound.email.classifySesFailure
import com.mundiapolis.library.notification.config.AwsSesProperties
import com.mundiapolis.library.notification.dto.EmailDeliveryException
import com.mundiapolis.library.notification.dto.EmailDeliveryFailureCode
import com.mundiapolis.library.notification.dto.EmailRecipient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

class AwsSesEmailProviderTest {
    @Test
    fun `send builds a traceable UTF-8 SES request`() {
        var captured: SendEmailRequest? = null
        val provider = provider { request ->
            captured = request
            "ses-message-123"
        }

        val receipt = provider.send(EmailRecipient("reader@example.test"), "Loan due", "Return it tomorrow.", DELIVERY_ID)

        assertThat(receipt.provider).isEqualTo("aws-ses")
        assertThat(receipt.messageReference).isEqualTo("ses-message-123")
        assertThat(captured?.fromEmailAddress).isEqualTo("library@example.test")
        assertThat(captured?.configurationSetName).isEqualTo("mundia-events")
        assertThat(captured?.destination?.toAddresses).containsExactly("reader@example.test")
        assertThat(captured?.content?.simple?.subject?.data).isEqualTo("Loan due")
        assertThat(captured?.content?.simple?.subject?.charset).isEqualTo("UTF-8")
        assertThat(captured?.content?.simple?.body?.text?.data).isEqualTo("Return it tomorrow.")
        assertThat(captured?.content?.simple?.body?.text?.charset).isEqualTo("UTF-8")
        assertThat(captured?.content?.simple?.headers).hasSize(1)
        assertThat(captured?.content?.simple?.headers?.single()?.name).isEqualTo("X-Mundia-Delivery-Id")
        assertThat(captured?.content?.simple?.headers?.single()?.value).isEqualTo(DELIVERY_ID.toString())
        assertThat(captured?.emailTags).hasSize(1)
        assertThat(captured?.emailTags?.single()?.name).isEqualTo("delivery_id")
        assertThat(captured?.emailTags?.single()?.value).isEqualTo(DELIVERY_ID.toString())
    }

    @Test
    fun `non ASCII recipient is rejected before calling SES`() {
        var called = false
        val provider = provider {
            called = true
            "unexpected"
        }

        assertThatThrownBy {
            provider.send(EmailRecipient("réader@example.test"), "Subject", "Body", DELIVERY_ID)
        }.isInstanceOfSatisfying(EmailDeliveryException::class.java) { failure ->
            assertThat(failure.failureCode).isEqualTo(EmailDeliveryFailureCode.PROVIDER_REJECTED)
        }
        assertThat(called).isFalse()
    }

    @Test
    fun `invalid provider message id is treated as an unavailable outcome`() {
        val provider = provider { " " }

        assertThatThrownBy {
            provider.send(EmailRecipient("reader@example.test"), "Subject", "Body", DELIVERY_ID)
        }.isInstanceOfSatisfying(EmailDeliveryException::class.java) { failure ->
            assertThat(failure.failureCode).isEqualTo(EmailDeliveryFailureCode.PROVIDER_UNAVAILABLE)
        }
    }

    @Test
    fun `enabled SES configuration rejects placeholders and unsafe timeouts`() {
        assertThat(properties(enabled = false).isSafeConfiguration).isTrue()
        assertThat(properties(fromAddress = "disabled@example.invalid").isSafeConfiguration).isFalse()
        assertThat(properties(configurationSetName = "disabled").isSafeConfiguration).isFalse()
        assertThat(properties(region = "local").isSafeConfiguration).isFalse()
        assertThat(properties(attemptTimeout = Duration.ofSeconds(9)).isSafeConfiguration).isFalse()
        assertThat(properties().isSafeConfiguration).isTrue()
    }

    @Test
    fun `SES failures are classified for the durable retry policy`() {
        assertThat(classifySesFailure(TooManyRequestsException {}))
            .isEqualTo(EmailDeliveryFailureCode.PROVIDER_RATE_LIMITED)
        assertThat(classifySesFailure(LimitExceededException {}))
            .isEqualTo(EmailDeliveryFailureCode.PROVIDER_RATE_LIMITED)
        assertThat(classifySesFailure(SendingPausedException {}))
            .isEqualTo(EmailDeliveryFailureCode.PROVIDER_UNAVAILABLE)
        assertThat(classifySesFailure(AccountSuspendedException {}))
            .isEqualTo(EmailDeliveryFailureCode.PROVIDER_REJECTED)
        assertThat(classifySesFailure(MessageRejected {}))
            .isEqualTo(EmailDeliveryFailureCode.PROVIDER_REJECTED)
        assertThat(classifySesFailure(IllegalStateException("credential resolution failed")))
            .isEqualTo(EmailDeliveryFailureCode.PROVIDER_UNAVAILABLE)
    }

    private fun provider(gateway: SesEmailGateway) = AwsSesEmailProvider(gateway, properties())

    private fun properties(
        enabled: Boolean = true,
        region: String = "eu-west-1",
        fromAddress: String = "library@example.test",
        configurationSetName: String = "mundia-events",
        callTimeout: Duration = Duration.ofSeconds(8),
        attemptTimeout: Duration = Duration.ofSeconds(7),
    ) = AwsSesProperties(enabled, region, fromAddress, configurationSetName, callTimeout, attemptTimeout)

    private companion object {
        val DELIVERY_ID: UUID = UUID.fromString("10000000-0000-4000-8000-000000000001")
    }
}
