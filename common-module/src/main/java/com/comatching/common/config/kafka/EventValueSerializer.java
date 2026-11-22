package com.comatching.common.config.kafka;

import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

public class EventValueSerializer extends DelegatingByTypeSerializer {

    public EventValueSerializer() {
        super(Map.of(
                byte[].class, new ByteArraySerializer(),
                String.class, new StringSerializer(),
                Object.class, new JsonSerializer<>()
        ), true);
    }
}
