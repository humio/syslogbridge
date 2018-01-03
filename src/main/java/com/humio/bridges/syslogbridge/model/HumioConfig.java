package com.humio.bridges.syslogbridge.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "humio")
@Data
public class HumioConfig {
    /**
     * Humio host and protocol, i.e. https://cloud.humio.com
     */
    private String urlPrefix;
}
