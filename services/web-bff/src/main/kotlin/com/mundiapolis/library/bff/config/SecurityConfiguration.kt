package com.mundiapolis.library.bff.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.Customizer.withDefaults
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.session.web.http.CookieSerializer
import org.springframework.session.web.http.DefaultCookieSerializer

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    fun sessionCookieSerializer(properties: BffProperties): CookieSerializer =
        DefaultCookieSerializer().apply {
            setCookieName(SESSION_COOKIE)
            setCookiePath("/")
            setUseHttpOnlyCookie(true)
            setUseSecureCookie(properties.secureCookies)
            setSameSite("Lax")
        }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        authorizationRequestResolver: OAuth2AuthorizationRequestResolver,
        properties: BffProperties,
    ): SecurityFilterChain {
        val csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse()
        csrfRepository.setCookieCustomizer { cookie ->
            cookie.path("/")
                .secure(properties.secureCookies)
                .sameSite("Lax")
        }
        val csrfRequestHandler = CsrfTokenRequestAttributeHandler()
        csrfRequestHandler.setCsrfRequestAttributeName(null)

        return http
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers(
                        "/actuator/health/**",
                        "/api/v1/auth/session",
                        "/api/v1/auth/csrf",
                        "/oauth2/authorization/**",
                        "/login/oauth2/code/**",
                        "/error",
                    ).permitAll()
                    .anyRequest().authenticated()
            }
            .csrf { csrf ->
                csrf.csrfTokenRepository(csrfRepository)
                    .csrfTokenRequestHandler(csrfRequestHandler)
            }
            .oauth2Login { login ->
                login.authorizationEndpoint { endpoint ->
                    endpoint.authorizationRequestResolver(authorizationRequestResolver)
                }
            }
            .oauth2Client(withDefaults())
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }
            .logout { logout ->
                logout.logoutUrl("/api/v1/auth/logout")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies(SESSION_COOKIE, CSRF_COOKIE)
                    .logoutSuccessHandler(HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
            }
            .headers { headers ->
                headers.contentTypeOptions(withDefaults())
                    .frameOptions { frame -> frame.deny() }
                    .httpStrictTransportSecurity { hsts ->
                        hsts.includeSubDomains(true).preload(true)
                    }
            }
            .build()
    }

    @Bean
    fun authorizationRequestResolver(
        registrations: ClientRegistrationRepository,
    ): OAuth2AuthorizationRequestResolver {
        val resolver = DefaultOAuth2AuthorizationRequestResolver(
            registrations,
            "/oauth2/authorization",
        )
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())
        return resolver
    }

    private companion object {
        const val SESSION_COOKIE = "MUNDIA_SESSION"
        const val CSRF_COOKIE = "XSRF-TOKEN"
    }
}
