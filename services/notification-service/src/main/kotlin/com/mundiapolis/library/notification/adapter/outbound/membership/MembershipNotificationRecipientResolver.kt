package com.mundiapolis.library.notification.adapter.outbound.membership

import com.mundiapolis.library.notification.config.MembershipRecipientClientProperties
import com.mundiapolis.library.notification.config.MembershipRecipientConfiguration
import com.mundiapolis.library.notification.dto.EmailDeliveryException
import com.mundiapolis.library.notification.dto.EmailDeliveryFailureCode
import com.mundiapolis.library.notification.dto.EmailRecipient
import com.mundiapolis.library.notification.service.NotificationRecipientResolver
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

class MembershipNotificationRecipientResolver(
    private val authorizedClientManager: OAuth2AuthorizedClientManager,
    private val membershipRestClient: RestClient,
    private val objectMapper: ObjectMapper,
    private val properties: MembershipRecipientClientProperties,
) : NotificationRecipientResolver {
    override fun resolve(memberId: UUID): EmailRecipient {
        try {
            val request = OAuth2AuthorizeRequest
                .withClientRegistrationId(MembershipRecipientConfiguration.REGISTRATION_ID)
                .principal(MembershipRecipientConfiguration.SERVICE_PRINCIPAL)
                .build()
            val authorizedClient = authorizedClientManager.authorize(request)
                ?: throw EmailDeliveryException(EmailDeliveryFailureCode.RECIPIENT_UNAVAILABLE)
            if (
                MembershipRecipientConfiguration.PROFILE_READ_ANY_SCOPE !in
                authorizedClient.accessToken.scopes
            ) {
                throw EmailDeliveryException(EmailDeliveryFailureCode.RECIPIENT_UNAVAILABLE)
            }
            return membershipRestClient.get()
                .uri("/api/v1/members/{memberId}/profile", memberId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${authorizedClient.accessToken.tokenValue}")
                .exchange { _, response ->
                    when (response.statusCode.value()) {
                        404 -> throw EmailDeliveryException(EmailDeliveryFailureCode.RECIPIENT_NOT_FOUND)
                        401, 403, 429, 502, 503, 504 ->
                            throw EmailDeliveryException(EmailDeliveryFailureCode.RECIPIENT_UNAVAILABLE)
                    }
                    if (!response.statusCode.is2xxSuccessful) {
                        throw EmailDeliveryException(EmailDeliveryFailureCode.RECIPIENT_UNAVAILABLE)
                    }
                    val mediaType = response.headers.contentType
                    if (mediaType == null || !mediaType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
                        throw EmailDeliveryException(EmailDeliveryFailureCode.RECIPIENT_UNAVAILABLE)
                    }
                    if (response.headers.contentLength > properties.maximumResponseBytes) {
                        throw EmailDeliveryException(EmailDeliveryFailureCode.RECIPIENT_UNAVAILABLE)
                    }
                    val body = response.body.readNBytes(properties.maximumResponseBytes + 1)
                    if (body.size > properties.maximumResponseBytes) {
                        throw EmailDeliveryException(EmailDeliveryFailureCode.RECIPIENT_UNAVAILABLE)
                    }
                    decode(memberId, body)
                }
        } catch (failure: EmailDeliveryException) {
            throw failure
        } catch (_: ResourceAccessException) {
            throw EmailDeliveryException(EmailDeliveryFailureCode.RECIPIENT_UNAVAILABLE)
        } catch (_: RestClientException) {
            throw EmailDeliveryException(EmailDeliveryFailureCode.RECIPIENT_UNAVAILABLE)
        } catch (_: Exception) {
            throw EmailDeliveryException(EmailDeliveryFailureCode.RECIPIENT_UNAVAILABLE)
        }
    }

    private fun decode(expectedMemberId: UUID, body: ByteArray): EmailRecipient {
        val profile = runCatching { objectMapper.readValue(body, MemberProfileResponse::class.java) }
            .getOrElse { throw EmailDeliveryException(EmailDeliveryFailureCode.RECIPIENT_UNAVAILABLE) }
        if (
            profile.memberId != expectedMemberId || profile.email.length !in 3..320 ||
            profile.email.any(Char::isISOControl) || !EMAIL.matches(profile.email) ||
            profile.fullName.length !in 1..200 || profile.fullName.any(Char::isISOControl) ||
            profile.universityId < 1 || profile.updatedAt < profile.createdAt
        ) {
            throw EmailDeliveryException(EmailDeliveryFailureCode.RECIPIENT_UNAVAILABLE)
        }
        return EmailRecipient(profile.email)
    }

    private data class MemberProfileResponse(
        val memberId: UUID,
        val email: String,
        val fullName: String,
        val universityId: Int,
        val status: AccountStatus,
        val role: MembershipRole,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    private enum class AccountStatus { PENDING, APPROVED, REJECTED }
    private enum class MembershipRole { USER, ADMIN }

    private companion object {
        val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")
    }
}
