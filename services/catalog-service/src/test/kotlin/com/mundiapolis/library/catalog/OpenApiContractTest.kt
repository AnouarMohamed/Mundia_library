package com.mundiapolis.library.catalog

import com.mundiapolis.library.catalog.adapter.`in`.web.CatalogReadController
import com.mundiapolis.library.catalog.adapter.`in`.web.CatalogCommandController
import com.mundiapolis.library.catalog.adapter.`in`.web.CatalogCommandResponse
import com.mundiapolis.library.catalog.adapter.`in`.web.CreateAuthorRequest
import com.mundiapolis.library.catalog.adapter.`in`.web.CreateEditionRequest
import com.mundiapolis.library.catalog.adapter.`in`.web.CreateWorkRequest
import com.mundiapolis.library.catalog.dto.Author
import com.mundiapolis.library.catalog.dto.CatalogSearchResult
import com.mundiapolis.library.catalog.dto.Edition
import com.mundiapolis.library.catalog.dto.Work
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

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
        assertThat(contract["info"]["version"].stringValue()).isEqualTo("1.1.0")
        assertThat(contractOperations()).isEqualTo(controllerOperations())
    }

    @Test
    fun `published JSON fields match transport models exactly`() {
        assertSchemaFields("Author", Author::class.java)
        assertSchemaFields("Work", Work::class.java)
        assertSchemaFields("Edition", Edition::class.java)
        assertSchemaFields("CatalogSearchResult", CatalogSearchResult::class.java)
        assertSchemaFields("CreateAuthorRequest", CreateAuthorRequest::class.java)
        assertSchemaFields("CreateWorkRequest", CreateWorkRequest::class.java)
        assertSchemaFields("CreateEditionRequest", CreateEditionRequest::class.java)
        assertSchemaFields("CatalogCommandResponse", CatalogCommandResponse::class.java)
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
            listOf("get", "post").forEach { httpMethod ->
                val operation = paths[path][httpMethod]
                if (operation != null) {
                    val scopes = operation["x-required-scopes"]
                    operations[Route(httpMethod, path)] = (0 until scopes.size())
                        .map { index -> scopes[index].stringValue() }
                        .toSet()
                }
            }
        }
        return operations
    }

    private fun controllerOperations(): Map<Route, Set<String>> = listOf(
        CatalogReadController::class.java,
        CatalogCommandController::class.java,
    ).flatMap { controller ->
        val basePath = requireNotNull(controller.getAnnotation(RequestMapping::class.java)).value.single()
        controller.declaredMethods.mapNotNull { method ->
            val get = method.getAnnotation(GetMapping::class.java)
            val post = method.getAnnotation(PostMapping::class.java)
            val httpMethod = when {
                get != null -> "get"
                post != null -> "post"
                else -> return@mapNotNull null
            }
            val relativePath = get?.value?.single() ?: requireNotNull(post).value.single()
            val authorization = requireNotNull(method.getAnnotation(PreAuthorize::class.java))
            val scopes = SCOPE_PATTERN.findAll(authorization.value)
                .map { match -> match.groupValues[1] }
                .toSet()
            Route(httpMethod, basePath + relativePath) to scopes
        }
    }.toMap()

    private data class Route(val method: String, val path: String)

    private companion object {
        const val CONTRACT_RESOURCE = "/static/openapi/catalog-v1.json"
        val SCOPE_PATTERN = Regex("SCOPE_([a-z0-9.-]+)")
    }
}
