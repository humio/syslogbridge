package com.humio.bridges.syslogbridge.services;

import org.springframework.integration.transformer.GenericTransformer;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.PathMatcher;

import java.net.MalformedURLException;
import java.net.URL;

@Component
public class UrlPatternHeaderEnricher implements GenericTransformer<Message<?>, Message<?>> {
    private final PathMatcher pathMatcher;

    public UrlPatternHeaderEnricher(PathMatcher pathMatcher) {
        this.pathMatcher = pathMatcher;
    }

    @Override
    public Message<?> transform(Message<?> source) {
        try {
            return MessageBuilder.fromMessage(source).copyHeaders(pathMatcher.extractUriTemplateVariables((source.getHeaders().get("http_requestPattern", String.class)), new URL(source.getHeaders().get("http_requestUrl", String.class)).getPath())).build();
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid URL", e);
        }
    }
}
