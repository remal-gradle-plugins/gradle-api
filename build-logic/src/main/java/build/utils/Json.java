package build.utils;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY;
import static com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS;
import static com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_SINGLE_QUOTES;
import static com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_TRAILING_COMMA;
import static com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES;
import static com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_YAML_COMMENTS;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY;
import static com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT;
import static com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_WITH_ZONE_ID;
import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;

public abstract class Json {

    private static final JsonFactory JSON_FACTORY = new JsonFactoryBuilder()
        .enable(ALLOW_JAVA_COMMENTS)
        .enable(ALLOW_YAML_COMMENTS)
        .enable(ALLOW_SINGLE_QUOTES)
        .enable(ALLOW_UNQUOTED_FIELD_NAMES)
        .enable(ALLOW_TRAILING_COMMA)
        .build();

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder(JSON_FACTORY)
        .findAndAddModules()
        .addModule(new JsonHooksModule())
        .enable(FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(FAIL_ON_NUMBERS_FOR_ENUMS)
        .enable(FAIL_ON_IGNORED_PROPERTIES)
        .disable(FAIL_ON_TRAILING_TOKENS)
        .enable(INDENT_OUTPUT)
        .disable(WRITE_DATES_AS_TIMESTAMPS)
        .enable(WRITE_DATES_WITH_ZONE_ID)
        .disable(WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
        .disable(SORT_PROPERTIES_ALPHABETICALLY)
        .disable(ORDER_MAP_ENTRIES_BY_KEYS)
        .defaultPropertyInclusion(JsonInclude.Value.construct(NON_EMPTY, NON_EMPTY))
        .build();

    public static final ObjectReader JSON_READER = JSON_MAPPER.reader();
    public static final ObjectWriter JSON_WRITER = JSON_MAPPER.writer();

}
