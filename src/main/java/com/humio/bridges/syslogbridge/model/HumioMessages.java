package com.humio.bridges.syslogbridge.model;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class HumioMessages {
    private final String type;
//    @Singular
//    private final Map<String, ?> fields;
    @Singular
    private final List<String> messages;
}
