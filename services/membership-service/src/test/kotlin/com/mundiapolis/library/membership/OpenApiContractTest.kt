package com.mundiapolis.library.membership

import com.mundiapolis.library.membership.adapter.`in`.web.MembershipReadController
import com.mundiapolis.library.membership.dto.IdentityEvidenceRef
import com.mundiapolis.library.membership.dto.MemberEligibility
import com.mundiapolis.library.membership.dto.MemberProfile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.JsonNode
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory

class OpenApiContractTest {
    private val contract: JsonNode = requireNotNull(
        OpenApiContractTest::class.java.getResourceAsStream(CONTRACT_RESOURCE),
    ).use(ObjectMapper()::readTree)

    @Test
    fun `published contract contains no duplicate JSON object keys`() {
        val strictMapper = ObjectMapper(
            JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build(),
        )

        assertThatCode {
            requireNotNull(
                OpenApiContractTest::class.java.getResourceAsStream(CONTRACT_RESOURCE),
            ).use(strictMapper::readTree)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `published routes and scopes match the controller exactly`() {
        assertThat(contract["openapi"].stringValue()).isEqualTo("3.1.0")
        assertThat(contract["info"]["version"].stringValue()).isEqualTo("1.0.0")
        assertThat(contractOperations()).isEqualTo(controllerOperations())
    }

    @Test
    fun `published JSON fields match transport models exactly`() {
        assertSchemaFields("MemberProfile", MemberProfile::class.java)
        assertSchemaFields("MemberEligibility", MemberEligibility::class.java)
        assertSchemaFields("IdentityEvidenceRef", IdentityEvidenceRef::class.java)
    }

    private fun assertSchemaFields(schemaName: String, model: Class<*>) {
        val schema = contract["components"]["schemas"][schemaName]
        val published = schema["properties"].propertyNames().toSet()
        val implemented = model.declaredFields
            .filterNot { it.isSynthetic || it.name == "Companion" }
            .map { it.name }
            .toSet()

        assertThat(schema["additionalProperties"].booleanValue()).isFalse()
        assertThat(published).isEqualTo(implemented)
    }

    private fun contractOperations(): Map<Route, Set<String>> {
        val paths = contract["paths"]
        val operations = mutableMapOf<Route, Set<String>>()
        paths.propertyNames().forEach { path ->
            val operation = paths[path]["get"]
            val scopes = operation["x-required-scopes"]
            operations[Route("get", path)] = (0 until scopes.size())
                .map { index -> scopes[index].stringValue() }
                .toSet()
        }
        return operations
    }

    private fun controllerOperations(): Map<Route, Set<String>> {
        val basePath = requireNotNull(
            MembershipReadController::class.java.getAnnotation(RequestMapping::class.java),
        ).value.single()
        return MembershipReadController::class.java.declaredMethods.mapNotNull { method ->
            val mapping = method.getAnnotation(GetMapping::class.java) ?: return@mapNotNull null
            val authorization = requireNotNull(method.getAnnotation(PreAuthorize::class.java))
            val scopes = SCOPE_PATTERN.findAll(authorization.value)
                .map { match -> match.groupValues[1] }
                .toSet()
            Route("get", basePath + mapping.value.single()) to scopes
        }.toMap()
    }

    private data class Route(val method: String, val path: String)

    private companion object {
        const val CONTRACT_RESOURCE = "/static/openapi/membership-v1.json"
        val SCOPE_PATTERN = Regex("SCOPE_([a-z0-9.-]+)")
    }
}
