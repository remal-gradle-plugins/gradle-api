package build.utils;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.BeanDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanSerializer;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import java.io.IOException;

class JsonHooksModule extends SimpleModule {

    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);

        context.addBeanSerializerModifier(new JsonHooksBeanSerializerModifier());
        context.addBeanDeserializerModifier(new JsonHooksBeanDeserializerModifier());
    }

    private static class JsonHooksBeanSerializerModifier extends BeanSerializerModifier {

        @Override
        public JsonSerializer<?> modifySerializer(
            SerializationConfig config,
            BeanDescription beanDesc,
            JsonSerializer<?> serializer
        ) {
            if (JsonHooks.class.isAssignableFrom(beanDesc.getBeanClass())) {
                return new BeanSerializer((BeanSerializer) serializer) {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    @Override
                    public void serialize(
                        Object value,
                        JsonGenerator gen,
                        SerializerProvider serializers
                    ) throws IOException {
                        if (value instanceof JsonHooks hooks) {
                            hooks.beforeSerialization();
                        }

                        ((JsonSerializer) serializer).serialize(value, gen, serializers);
                    }
                };
            }

            return super.modifySerializer(config, beanDesc, serializer);
        }

    }

    private static class JsonHooksBeanDeserializerModifier extends BeanDeserializerModifier {

        @Override
        public JsonDeserializer<?> modifyDeserializer(
            DeserializationConfig config,
            BeanDescription beanDesc,
            JsonDeserializer<?> deserializer
        ) {
            if (JsonHooks.class.isAssignableFrom(beanDesc.getBeanClass())) {
                return new BeanDeserializer((BeanDeserializer) deserializer) {
                    @Override
                    public Object deserialize(
                        JsonParser p,
                        DeserializationContext ctxt
                    ) throws IOException {
                        var obj = deserializer.deserialize(p, ctxt);
                        if (obj instanceof JsonHooks hooks) {
                            hooks.afterDeserialization();
                        }
                        return obj;
                    }
                };
            }

            return super.modifyDeserializer(config, beanDesc, deserializer);
        }

    }

}
