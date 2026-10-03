package com.mundiapolis.library.notification

import com.mundiapolis.library.notification.adapter.`in`.web.NotificationController
import com.mundiapolis.library.notification.dto.NotificationItem
import com.mundiapolis.library.notification.dto.NotificationPage
import com.mundiapolis.library.notification.dto.NotificationPreference
import com.mundiapolis.library.notification.dto.EmailSuppressionRemoval
import com.mundiapolis.library.notification.dto.RemoveEmailSuppressionRequest
import com.mundiapolis.library.notification.dto.UpdateNotificationPreferenceRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class OpenApiContractTest {
    private val contract: JsonNode = requireNotNull(javaClass.getResourceAsStream(CONTRACT_RESOURCE))
        .use(ObjectMapper()::readTree)

    @Test
    fun `contract has unique keys and exact controller routes`() {
        val strict = ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        assertThatCode { requireNotNull(javaClass.getResourceAsStream(CONTRACT_RESOURCE)).use(strict::readTree) }
            .doesNotThrowAnyException()
        assertThat(contract["openapi"].stringValue()).isEqualTo("3.1.0")
        assertThat(contractOperations()).isEqualTo(controllerOperations())
    }

    @Test
    fun `transport schemas match Kotlin models`() {
        assertSchemaFields("NotificationItem", NotificationItem::class.java)
        assertSchemaFields("NotificationPage", NotificationPage::class.java)
        assertSchemaFields("NotificationPreference", NotificationPreference::class.java)
        assertSchemaFields("EmailSuppressionRemoval", EmailSuppressionRemoval::class.java)
        assertSchemaFields("RemoveEmailSuppressionRequest", RemoveEmailSuppressionRequest::class.java)
        assertSchemaFields("UpdateNotificationPreferenceRequest", UpdateNotificationPreferenceRequest::class.java)
    }

    private fun assertSchemaFields(name: String, model: Class<*>) {
        val schema = contract["components"]["schemas"][name]
        assertThat(schema["additionalProperties"].booleanValue()).isFalse()
        assertThat(schema["properties"].propertyNames().toSet()).isEqualTo(
            model.declaredFields.filterNot { it.isSynthetic || it.name == "Companion" }.map { it.name }.toSet(),
        )
    }

    private fun contractOperations(): Map<Route, Set<String>> {
        val result = mutableMapOf<Route, Set<String>>()
        contract["paths"].propertyNames().forEach { path ->
            contract["paths"][path].propertyNames().forEach { method ->
                val scopes = contract["paths"][path][method]["x-required-scopes"]
                result[Route(method, path)] = (0 until scopes.size()).map { scopes[it].stringValue() }.toSet()
            }
        }
        return result
    }

    private fun controllerOperations(): Map<Route, Set<String>> {
        val base = requireNotNull(NotificationController::class.java.getAnnotation(RequestMapping::class.java)).value.single()
        return NotificationController::class.java.declaredMethods.mapNotNull { method ->
            val path = method.getAnnotation(GetMapping::class.java)?.value?.singleOrNull()
                ?: method.getAnnotation(PatchMapping::class.java)?.value?.singleOrNull()
                ?: method.getAnnotation(PostMapping::class.java)?.value?.singleOrNull()
                ?: method.getAnnotation(PutMapping::class.java)?.value?.singleOrNull()
                ?: return@mapNotNull null
            val verb = when {
                method.isAnnotationPresent(GetMapping::class.java) -> "get"
                method.isAnnotationPresent(PatchMapping::class.java) -> "patch"
                method.isAnnotationPresent(PostMapping::class.java) -> "post"
                else -> "put"
            }
            val scopes = SCOPE_PATTERN.findAll(requireNotNull(method.getAnnotation(PreAuthorize::class.java)).value)
                .map { it.groupValues[1] }.toSet()
            Route(verb, base + path) to scopes
        }.toMap()
    }

    private data class Route(val method: String, val path: String)

    private companion object {
        const val CONTRACT_RESOURCE = "/static/openapi/notification-v1.json"
        val SCOPE_PATTERN = Regex("SCOPE_([a-z0-9.-]+)")
    }
}
