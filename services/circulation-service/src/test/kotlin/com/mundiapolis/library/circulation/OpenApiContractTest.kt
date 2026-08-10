package com.mundiapolis.library.circulation

import com.mundiapolis.library.circulation.adapter.`in`.web.AdjustFineRequest
import com.mundiapolis.library.circulation.adapter.`in`.web.AssessFineRequest
import com.mundiapolis.library.circulation.adapter.`in`.web.ChangeCopyConditionRequest
import com.mundiapolis.library.circulation.adapter.`in`.web.CirculationStatusController
import com.mundiapolis.library.circulation.adapter.`in`.web.CirculationPolicyResponse
import com.mundiapolis.library.circulation.adapter.`in`.web.CirculationReadController
import com.mundiapolis.library.circulation.adapter.`in`.web.FineCommandController
import com.mundiapolis.library.circulation.adapter.`in`.web.FineCommandResponse
import com.mundiapolis.library.circulation.adapter.`in`.web.InventoryCommandController
import com.mundiapolis.library.circulation.adapter.`in`.web.InventoryCommandResponse
import com.mundiapolis.library.circulation.adapter.`in`.web.LoanCommandController
import com.mundiapolis.library.circulation.adapter.`in`.web.LoanCommandResponse
import com.mundiapolis.library.circulation.adapter.`in`.web.MemberEligibilityResponse
import com.mundiapolis.library.circulation.adapter.`in`.web.PlaceReservationRequest
import com.mundiapolis.library.circulation.adapter.`in`.web.PolicyCommandController
import com.mundiapolis.library.circulation.adapter.`in`.web.ReservationCommandController
import com.mundiapolis.library.circulation.adapter.`in`.web.ReservationCommandResponse
import com.mundiapolis.library.circulation.adapter.`in`.web.RecordFinePaymentRequest
import com.mundiapolis.library.circulation.adapter.`in`.web.RegisterCopyRequest
import com.mundiapolis.library.circulation.adapter.`in`.web.RelocateCopyRequest
import com.mundiapolis.library.circulation.adapter.`in`.web.RequestLoanRequest
import com.mundiapolis.library.circulation.adapter.`in`.web.UpdateCirculationPolicyRequest
import com.mundiapolis.library.circulation.application.port.inbound.CirculationStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.PutMapping
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

class OpenApiContractTest {
    private val contract: JsonNode = requireNotNull(
        OpenApiContractTest::class.java.getResourceAsStream(CONTRACT_RESOURCE),
    ).use(ObjectMapper()::readTree)

    @Test
    fun `published contract routes and scopes match controller annotations exactly`() {
        assertThat(contract["openapi"].stringValue()).isEqualTo("3.1.0")
        assertThat(contract["info"]["version"].stringValue()).isEqualTo("1.0.0")

        assertThat(contractOperations()).isEqualTo(controllerOperations())
    }

    @Test
    fun `every command publishes the actor scoped idempotency contract`() {
        val commandOperations = contractOperations().keys.filter { it.method in setOf("post", "put") }

        assertThat(commandOperations).isNotEmpty()
        commandOperations.forEach { route ->
            val operation = contract["paths"][route.path][route.method]
            val parameters = operation["parameters"]
            val parameterReferences = (0 until parameters.size()).map { index ->
                parameters[index]["\$ref"]?.stringValue()
            }
            assertThat(parameterReferences)
                .describedAs("%s %s parameters", route.method.uppercase(), route.path)
                .contains("#/components/parameters/IdempotencyKey")

            val responseReferences = operation["responses"].propertyNames().map { status ->
                operation["responses"][status]["\$ref"]?.stringValue()
            }.toList()
            assertThat(responseReferences)
                .describedAs("%s %s responses", route.method.uppercase(), route.path)
                .anyMatch { reference ->
                    reference == "#/components/responses/LoanCreated" ||
                        reference == "#/components/responses/LoanCommandSucceeded" ||
                        reference == "#/components/responses/FineCreated" ||
                        reference == "#/components/responses/FineCommandSucceeded" ||
                        reference == "#/components/responses/InventoryCreated" ||
                        reference == "#/components/responses/InventoryCommandSucceeded" ||
                        reference == "#/components/responses/ReservationCreated" ||
                        reference == "#/components/responses/ReservationCommandSucceeded" ||
                        reference == "#/components/responses/PolicyUpdated"
                }
        }

        val idempotencySchema = contract["components"]["parameters"]["IdempotencyKey"]["schema"]
        assertThat(idempotencySchema["minLength"].intValue()).isEqualTo(16)
        assertThat(idempotencySchema["maxLength"].intValue()).isEqualTo(128)
        assertThat(
            contract["components"]["headers"]["IdempotencyReplayed"]["required"].booleanValue(),
        ).isTrue()
    }

    @Test
    fun `published JSON field sets match the transport models exactly`() {
        assertSchemaFields("CirculationStatus", CirculationStatus::class.java)
        assertSchemaFields("CirculationPolicyResponse", CirculationPolicyResponse::class.java)
        assertSchemaFields("MemberEligibilityResponse", MemberEligibilityResponse::class.java)
        assertSchemaFields("RequestLoanRequest", RequestLoanRequest::class.java)
        assertSchemaFields("LoanCommandResponse", LoanCommandResponse::class.java)
        assertSchemaFields("PlaceReservationRequest", PlaceReservationRequest::class.java)
        assertSchemaFields("ReservationCommandResponse", ReservationCommandResponse::class.java)
        assertSchemaFields(
            "UpdateCirculationPolicyRequest",
            UpdateCirculationPolicyRequest::class.java,
        )
        assertSchemaFields("AssessFineRequest", AssessFineRequest::class.java)
        assertSchemaFields("RecordFinePaymentRequest", RecordFinePaymentRequest::class.java)
        assertSchemaFields("AdjustFineRequest", AdjustFineRequest::class.java)
        assertSchemaFields("FineCommandResponse", FineCommandResponse::class.java)
        assertSchemaFields("RegisterCopyRequest", RegisterCopyRequest::class.java)
        assertSchemaFields("ChangeCopyConditionRequest", ChangeCopyConditionRequest::class.java)
        assertSchemaFields("RelocateCopyRequest", RelocateCopyRequest::class.java)
        assertSchemaFields("InventoryCommandResponse", InventoryCommandResponse::class.java)
    }

    @Test
    fun `every authenticated operation documents admission control outcomes`() {
        contractOperations().keys.forEach { route ->
            val responses = contract["paths"][route.path][route.method]["responses"]
            assertThat(responses.has("429"))
                .describedAs("%s %s must document rate limiting", route.method, route.path)
                .isTrue()
            assertThat(responses.has("503"))
                .describedAs("%s %s must document fail-closed admission", route.method, route.path)
                .isTrue()
        }
    }

    private fun assertSchemaFields(schemaName: String, model: Class<*>) {
        val schema = contract["components"]["schemas"][schemaName]
        val published = schema["properties"].propertyNames().toSet()
        val requiredNode = schema["required"]
        val required = (0 until requiredNode.size())
            .map { index -> requiredNode[index].stringValue() }
            .toSet()
        val implemented = model.declaredFields
            .filterNot { it.isSynthetic || it.name == "Companion" }
            .map { it.name }
            .toSet()

        assertThat(schema["additionalProperties"].booleanValue())
            .describedAs("%s must reject undocumented fields", schemaName)
            .isFalse()
        assertThat(published).isEqualTo(implemented)
        assertThat(required).isEqualTo(implemented)
    }

    private fun contractOperations(): Map<Route, Set<String>> {
        val paths = contract["paths"]
        val operations = mutableMapOf<Route, Set<String>>()
        paths.propertyNames().forEach { path ->
            paths[path].propertyNames()
                .filter(SUPPORTED_HTTP_METHODS::contains)
                .forEach { method ->
                    val scopeNode = paths[path][method]["x-required-scopes"]
                    require(scopeNode.isArray) { "$method $path must publish x-required-scopes" }
                    val scopes = (0 until scopeNode.size())
                        .map { index -> scopeNode[index].stringValue() }
                        .toSet()
                    operations[Route(method, path)] = scopes
                }
        }
        return operations
    }

    private fun controllerOperations(): Map<Route, Set<String>> = CONTROLLERS.flatMap { controller ->
        val basePath = requireNotNull(controller.getAnnotation(RequestMapping::class.java))
            .value
            .single()
        controller.declaredMethods.mapNotNull { method ->
            val route = when {
                method.isAnnotationPresent(GetMapping::class.java) -> Route(
                    "get",
                    basePath + method.getAnnotation(GetMapping::class.java).value.singleOrNull().orEmpty(),
                )
                method.isAnnotationPresent(PostMapping::class.java) -> Route(
                    "post",
                    basePath + method.getAnnotation(PostMapping::class.java).value.singleOrNull().orEmpty(),
                )
                method.isAnnotationPresent(PutMapping::class.java) -> Route(
                    "put",
                    basePath + method.getAnnotation(PutMapping::class.java).value.singleOrNull().orEmpty(),
                )
                else -> null
            } ?: return@mapNotNull null
            val authorization = requireNotNull(method.getAnnotation(PreAuthorize::class.java)) {
                "${controller.simpleName}.${method.name} must declare authorization"
            }
            val scopes = SCOPE_PATTERN.findAll(authorization.value)
                .map { match -> match.groupValues[1] }
                .toSet()
            require(scopes.isNotEmpty()) {
                "${controller.simpleName}.${method.name} must declare at least one scope"
            }
            route to scopes
        }
    }.toMap()

    private data class Route(val method: String, val path: String)

    private companion object {
        const val CONTRACT_RESOURCE = "/static/openapi/circulation-v1.json"
        val CONTROLLERS = listOf(
            CirculationStatusController::class.java,
            CirculationReadController::class.java,
            LoanCommandController::class.java,
            FineCommandController::class.java,
            InventoryCommandController::class.java,
            ReservationCommandController::class.java,
            PolicyCommandController::class.java,
        )
        val SUPPORTED_HTTP_METHODS = setOf("get", "post", "put", "patch", "delete")
        val SCOPE_PATTERN = Regex("SCOPE_([a-z0-9.-]+)")
    }
}
