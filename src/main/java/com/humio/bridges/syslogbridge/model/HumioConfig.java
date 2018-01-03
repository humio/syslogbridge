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
    /**
     * Humio dataspace
     */
    private String dataspace;

    /**
     * TCP port for accepting syslog
     */
    private int syslogPort;
    /**
     * Humio ingesttoken
     */
    private String ingesttoken;

    /**
     * Number of executors that will take lines off the tcp buffer and convert them to Messages
     */
    private int syslogExecutors;
    /**
     * Number of executors that will take batches of events and post them to Humio
     */
    private int httpExecutors;

    private List<String> grokPaths;
}
