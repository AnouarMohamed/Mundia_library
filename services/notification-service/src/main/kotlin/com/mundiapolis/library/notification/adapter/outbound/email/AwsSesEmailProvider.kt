package com.mundiapolis.library.notification.adapter.outbound.email

import aws.sdk.kotlin.services.sesv2.SesV2Client
import aws.sdk.kotlin.services.sesv2.model.AccountSuspendedException
import aws.sdk.kotlin.services.sesv2.model.BadRequestException
import aws.sdk.kotlin.services.sesv2.model.Body
import aws.sdk.kotlin.services.sesv2.model.Content
import aws.sdk.kotlin.services.sesv2.model.Destination
import aws.sdk.kotlin.services.sesv2.model.EmailContent
import aws.sdk.kotlin.services.sesv2.model.LimitExceededException
import aws.sdk.kotlin.services.sesv2.model.MailFromDomainNotVerifiedException
import aws.sdk.kotlin.services.sesv2.model.Message
import aws.sdk.kotlin.services.sesv2.model.MessageHeader
import aws.sdk.kotlin.services.sesv2.model.MessageRejected
import aws.sdk.kotlin.services.sesv2.model.MessageTag
import aws.sdk.kotlin.services.sesv2.model.NotFoundException
import aws.sdk.kotlin.services.sesv2.model.SendEmailRequest
import aws.sdk.kotlin.services.sesv2.model.SendingPausedException
import aws.sdk.kotlin.services.sesv2.model.TooManyRequestsException
import com.mundiapolis.library.notification.config.AwsSesProperties
import com.mundiapolis.library.notification.dto.EmailDeliveryException
import com.mundiapolis.library.notification.dto.EmailDeliveryFailureCode
import com.mundiapolis.library.notification.dto.EmailProviderReceipt
import com.mundiapolis.library.notification.dto.EmailRecipient
import com.mundiapolis.library.notification.service.EmailProvider
import kotlinx.coroutines.runBlocking
import java.util.UUID

class AwsSesEmailProvider(
    private val gateway: SesEmailGateway,
    private val properties: AwsSesProperties,
) : EmailProvider {
    override fun send(
        recipient: EmailRecipient,
        subject: String,
        body: String,
        deliveryId: UUID,
    ): EmailProviderReceipt {
        if (!recipient.address.all { it.code <= 127 }) {
            throw EmailDeliveryException(EmailDeliveryFailureCode.PROVIDER_REJECTED)
        }
        val messageId = gateway.send(
            SendEmailRequest {
                fromEmailAddress = properties.fromAddress
                configurationSetName = properties.configurationSetName
                destination = Destination { toAddresses = listOf(recipient.address) }
                content = EmailContent {
                    simple = Message {
                        this.subject = Content { data = subject; charset = UTF_8 }
                        this.body = Body { text = Content { data = body; charset = UTF_8 } }
                        headers = listOf(
                            MessageHeader {
                                name = DELIVERY_HEADER
                                value = deliveryId.toString()
                            },
                        )
                    }
                }
                emailTags = listOf(
                    MessageTag {
                        name = DELIVERY_TAG
                        value = deliveryId.toString()
                    },
                )
            },
        )
        if (messageId.isBlank() || messageId.length > MAXIMUM_MESSAGE_ID_LENGTH ||
            messageId.any(Char::isISOControl)
        ) {
            throw EmailDeliveryException(EmailDeliveryFailureCode.PROVIDER_UNAVAILABLE)
        }
        return EmailProviderReceipt(PROVIDER, messageId)
    }

    private companion object {
        const val PROVIDER = "aws-ses"
        const val DELIVERY_HEADER = "X-Mundia-Delivery-Id"
        const val DELIVERY_TAG = "delivery_id"
        const val UTF_8 = "UTF-8"
        const val MAXIMUM_MESSAGE_ID_LENGTH = 200
    }
}

fun interface SesEmailGateway {
    fun send(request: SendEmailRequest): String
}

class AwsSdkSesEmailGateway(private val client: SesV2Client) : SesEmailGateway {
    override fun send(request: SendEmailRequest): String = runBlocking {
        try {
            client.sendEmail(request).messageId
                ?: throw EmailDeliveryException(EmailDeliveryFailureCode.PROVIDER_UNAVAILABLE)
        } catch (failure: EmailDeliveryException) {
            throw failure
        } catch (failure: Exception) {
            throw EmailDeliveryException(classifySesFailure(failure))
        }
    }
}

internal fun classifySesFailure(failure: Exception): EmailDeliveryFailureCode = when (failure) {
    is TooManyRequestsException, is LimitExceededException -> EmailDeliveryFailureCode.PROVIDER_RATE_LIMITED
    is SendingPausedException -> EmailDeliveryFailureCode.PROVIDER_UNAVAILABLE
    is AccountSuspendedException,
    is MailFromDomainNotVerifiedException,
    is MessageRejected,
    is BadRequestException,
    is NotFoundException,
    -> EmailDeliveryFailureCode.PROVIDER_REJECTED
    else -> EmailDeliveryFailureCode.PROVIDER_UNAVAILABLE
}
