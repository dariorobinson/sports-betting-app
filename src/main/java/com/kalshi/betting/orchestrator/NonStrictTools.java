package com.kalshi.betting.orchestrator;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.beta.messages.BetaTool;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.swagger2.Swagger2Module;

/**
 * Builds {@link BetaTool}s from plain Java classes the same way the Anthropic SDK's own
 * {@code MessageCreateParams.Builder.addTool(Class)} does — except with {@code strict=false}.
 * Strict mode routes every tool schema through Anthropic's server-side grammar compiler (used to
 * guarantee tool-call output matches the schema exactly); that compiler has a real complexity
 * ceiling shared across all tools in a request, and registering enough tools/fields eventually
 * fails with "Schema is too complex for compilation." Non-strict tools skip that compiler — the
 * schema is used as a hint only — which every Claude tool-calling model already handles reliably.
 */
final class NonStrictTools {

    private NonStrictTools() {
    }

    static BetaTool from(Class<?> parametersType) {
        ObjectNode schema = extractSchema(parametersType);
        String description = schema.has("description") ? schema.remove("description").asText() : null;
        String name = toSnakeCase(parametersType.getSimpleName());

        BetaTool.InputSchema inputSchema =
                ObjectMappers.jsonMapper().convertValue(schema, BetaTool.InputSchema.class);

        BetaTool.Builder builder = BetaTool.builder()
                .name(name)
                .inputSchema(inputSchema)
                .strict(false);
        if (description != null) {
            builder.description(description);
        }
        return builder.build();
    }

    private static ObjectNode extractSchema(Class<?> type) {
        SchemaGeneratorConfigBuilder configBuilder =
                new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                        .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)
                        .with(new JacksonModule())
                        .with(new Swagger2Module());
        configBuilder.forFields().withRequiredCheck(field -> true);
        return (ObjectNode) new SchemaGenerator(configBuilder.build()).generateSchema(type);
    }

    /** Mirrors the Anthropic SDK's own camelCase-to-snake_case tool-name conversion. */
    private static String toSnakeCase(String input) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                char prev = input.charAt(i - 1);
                boolean prevLower = Character.isLowerCase(prev);
                boolean nextLower = i < input.length() - 1 && Character.isLowerCase(input.charAt(i + 1));
                if (prevLower || (nextLower && Character.isUpperCase(prev))) {
                    sb.append('_');
                }
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
