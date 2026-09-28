package com.mundiapolis.library.bff

import com.mundiapolis.library.bff.web.BffSessionController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.security.web.csrf.DefaultCsrfToken

class BffSessionControllerTest {
    private val controller = BffSessionController()

    @Test
    fun `anonymous session response exposes no identity`() {
        val response = controller.session(null)

        assertThat(response.body)
            .isEqualTo(BffSessionController.SessionView(false, null))
        assertThat(response.headers.cacheControl).isEqualTo("no-store")
    }

    @Test
    fun `authenticated session exposes only a bounded display name`() {
        val authentication = mock(Authentication::class.java)
        val principal = mock(OidcUser::class.java)
        `when`(authentication.isAuthenticated).thenReturn(true)
        `when`(authentication.principal).thenReturn(principal)
        `when`(principal.fullName).thenReturn("Library Member")

        val response = controller.session(authentication)

        assertThat(response.body)
            .isEqualTo(BffSessionController.SessionView(true, "Library Member"))
    }

    @Test
    fun `csrf response gives the SPA only the standard token fields`() {
        val response = controller.csrf(DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "token"))

        assertThat(response.body).isEqualTo(
            BffSessionController.CsrfView("X-XSRF-TOKEN", "_csrf", "token"),
        )
        assertThat(response.headers.cacheControl).isEqualTo("no-store")
    }
}
