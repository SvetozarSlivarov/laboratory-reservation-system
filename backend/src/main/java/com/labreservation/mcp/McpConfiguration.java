package com.labreservation.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.labreservation.bookings.AvailabilityService;
import com.labreservation.bookings.BookingDtos.AvailableSlot;
import com.labreservation.bookings.BookingDtos.BookingView;
import com.labreservation.bookings.BookingDtos.NewBooking;
import com.labreservation.bookings.BookingService;
import com.labreservation.catalog.CatalogDtos.LaboratoryView;
import com.labreservation.catalog.CatalogService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
class McpConfiguration {

    private static final String ACTOR = "actor";

    @Bean
    WebMvcStatelessServerTransport reservationMcpTransport(ObjectMapper json) {
        return WebMvcStatelessServerTransport.builder()
                .messageEndpoint("/mcp")
                .jsonMapper(new JacksonMcpJsonMapper(json))
                .contextExtractor(request -> request.principal()
                        .filter(Authentication.class::isInstance)
                        .map(Authentication.class::cast)
                        .filter(McpConfiguration::isRegularUser)
                        .map(actor -> UsernamePasswordAuthenticationToken.authenticated(
                                actor.getName(), null, AuthorityUtils.createAuthorityList("ROLE_USER")))
                        .map(actor -> McpTransportContext.create(Map.of(ACTOR, actor)))
                        .orElse(McpTransportContext.EMPTY))
                .build();
    }

    @Bean
    List<SyncToolSpecification> reservationTools(CatalogService catalog, AvailabilityService availability,
                                                 BookingService bookings, ObjectMapper json, Validator validator) {
        ObjectMapper arguments = json.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);

        return List.of(
                tool("list_laboratories", "List enabled laboratories that the signed-in student can browse.",
                        Empty.class, Laboratories.class, true,
                        input -> new Laboratories(catalog.listLaboratories()), json, arguments, validator),
                tool("list_available_workstations", "Find available workstation slots in a future time range.",
                        Range.class, Slots.class, true,
                        input -> new Slots(availability.search(time(input.from()), time(input.to()), input.laboratoryId())),
                        json, arguments, validator),
                tool("my_bookings", "List the signed-in student's bookings, including cancellations.",
                        Empty.class, Bookings.class, true,
                        input -> new Bookings(bookings.listOwn()), json, arguments, validator),
                tool("create_booking", "Reserve a workstation for the signed-in student at a selected start time.",
                        Create.class, BookingView.class, false,
                        input -> bookings.create(new NewBooking(input.workstationId(), time(input.startsAt()),
                                input.endsAt() == null ? null : time(input.endsAt()))),
                        json, arguments, validator),
                tool("cancel_booking", "Cancel a future reservation owned by the signed-in student.",
                        Cancel.class, BookingView.class, false,
                        input -> bookings.cancel(input.bookingId()), json, arguments, validator));
    }

    private static <I, O> SyncToolSpecification tool(
            String name, String description, Class<I> inputType, Class<O> outputType, boolean readOnly,
            Function<I, O> action, ObjectMapper json, ObjectMapper arguments, Validator validator) {
        var mapper = new JacksonMcpJsonMapper(json);
        boolean cancellation = name.equals("cancel_booking");
        var definition = Tool.builder().name(name).description(description)
                .inputSchema(mapper, JsonSchemaGenerator.generateForType(inputType))
                .outputSchema(mapper, outputSchema(json, outputType))
                .annotations(new ToolAnnotations(null, readOnly, cancellation, readOnly || cancellation, false, null))
                .build();
        return new SyncToolSpecification(definition, (context, request) -> {
            if (!(context.get(ACTOR) instanceof Authentication actor) || !isRegularUser(actor)) {
                return error("403 A regular application session is required");
            }
            I input;
            try {
                input = arguments.convertValue(request.arguments() == null ? Map.of() : request.arguments(), inputType);
            } catch (IllegalArgumentException exception) {
                return error("400 Invalid tool arguments");
            }
            if (!validator.validate(input).isEmpty()) {
                return error("400 Invalid tool arguments");
            }

            var previous = SecurityContextHolder.getContext();
            var callContext = SecurityContextHolder.createEmptyContext();
            callContext.setAuthentication(actor);
            SecurityContextHolder.setContext(callContext);
            try {
                var result = json.valueToTree(action.apply(input));
                return CallToolResult.builder().structuredContent(result).addTextContent(result.toString()).build();
            } catch (DateTimeParseException exception) {
                return error("400 Timestamps must be ISO 8601 with an offset");
            } catch (ResponseStatusException exception) {
                return error(exception.getStatusCode().value() + " " + exception.getReason());
            } catch (RuntimeException exception) {
                return error("500 The tool could not complete the request");
            } finally {
                SecurityContextHolder.setContext(previous);
            }
        });
    }

    private static String outputSchema(ObjectMapper json, Class<?> type) {
        try {
            var schema = json.readTree(JsonSchemaGenerator.generateForType(type));
            // These REST fields are serialized as explicit nulls when unset.
            for (String field : List.of("cancelledAt", "capabilities")) {
                for (var parent : schema.findParents(field)) {
                    ((ObjectNode) parent.get(field)).putArray("type").add("string").add("null");
                }
            }
            return schema.toString();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not generate MCP output schema", exception);
        }
    }

    private static Instant time(String value) {
        return OffsetDateTime.parse(value).toInstant();
    }

    private static boolean isRegularUser(Authentication actor) {
        return actor.isAuthenticated()
                && actor.getAuthorities().stream().anyMatch(role -> role.getAuthority().equals("ROLE_USER"))
                && actor.getAuthorities().stream().noneMatch(role -> role.getAuthority().equals("ROLE_ADMIN"));
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().isError(true).addTextContent(message).build();
    }

    record Empty() {}
    record Range(@JsonProperty(required = true) @NotNull String from,
                 @JsonProperty(required = true) @NotNull String to, @Positive Long laboratoryId) {}
    record Create(@JsonProperty(required = true) @NotNull @Positive Long workstationId,
                  @JsonProperty(required = true) @NotNull String startsAt, String endsAt) {}
    record Cancel(@JsonProperty(required = true) @NotNull @Positive Long bookingId) {}
    record Laboratories(List<LaboratoryView> laboratories) {}
    record Slots(List<AvailableSlot> slots) {}
    record Bookings(List<BookingView> bookings) {}
}
