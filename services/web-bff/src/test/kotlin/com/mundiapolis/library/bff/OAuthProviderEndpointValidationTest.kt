package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.config.OAuthClientConfiguration
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod

class OAuthProviderEndpointValidationTest {
    @Test
    fun `production permits a separate https issuer origin for cognito style providers`() {
        val registration = registration(
            authorizationUri = "https://library.auth.eu-west-1.amazoncognito.com/oauth2/authorize",
            tokenUri = "https://library.auth.eu-west-1.amazoncognito.com/oauth2/token",
        )

        assertThatCode {
            OAuthClientConfiguration().validateProviderEndpoints(
                InMemoryClientRegistrationRepository(registration),
                "production",
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `production rejects token endpoints on a different oauth origin`() {
        val registration = registration(
            authorizationUri = "https://identity.example.test/oauth2/authorize",
            tokenUri = "https://tokens.example.test/oauth2/token",
        )

        assertThatThrownBy {
            OAuthClientConfiguration().validateProviderEndpoints(
                InMemoryClientRegistrationRepository(registration),
                "production",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun registration(
        authorizationUri: String,
        tokenUri: String,
    ): ClientRegistration = ClientRegistration.withRegistrationId("institutional")
        .clientId("web-bff")
        .clientSecret("test-only-secret")
        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
        .redirectUri("https://library.example.test/login/oauth2/code/institutional")
        .scope("openid", "profile", "email")
        .authorizationUri(authorizationUri)
        .tokenUri(tokenUri)
        .jwkSetUri("https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_example/.well-known/jwks.json")
        .issuerUri("https://cognito-idp.eu-west-1.amazonaws.com/eu-west-1_example")
        .userNameAttributeName("sub")
        .clientName("Institutional login")
        .build()
}
