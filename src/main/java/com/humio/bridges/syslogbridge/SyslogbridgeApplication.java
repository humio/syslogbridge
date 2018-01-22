package com.humio.bridges.syslogbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.humio.bridges.syslogbridge.model.HumioConfig;
import com.humio.bridges.syslogbridge.model.HumioMessages;
import org.aopalliance.aop.Advice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.integration.aggregator.MessageCountReleaseStrategy;
import org.springframework.integration.annotation.IntegrationComponentScan;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.IntegrationFlows;
import org.springframework.integration.dsl.Transformers;
import org.springframework.integration.dsl.channel.MessageChannels;
import org.springframework.integration.handler.LoggingHandler;
import org.springframework.integration.handler.advice.RequestHandlerRetryAdvice;
import org.springframework.integration.http.dsl.Http;
import org.springframework.integration.transformer.GenericTransformer;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static java.util.Collections.singletonList;

@SpringBootApplication
@IntegrationComponentScan
public class SyslogbridgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(SyslogbridgeApplication.class, args);
    }

    @Bean
    public HumioConfig humioConfig() {
        return new HumioConfig();
    }

    @Bean
    public PathMatcher pathMatcher() {
        return new AntPathMatcher();
    }

    @Bean
    public IntegrationFlow syslogIntegrationFlow(HumioConfig humioConfig, Advice humioHttpClientAdvice) {
        return IntegrationFlows
                .from(Http.inboundChannelAdapter("/dataspaces/{dataspace}/{ingesttoken}/types/{type}")
                        .requestMapping(spec -> spec.methods(HttpMethod.POST))
                        .headerExpression("humio_dataspace", "#pathVariables.dataspace")
                        .headerExpression("humio_ingesttoken", "#pathVariables.ingesttoken")
                        .headerExpression("humio_type", "#pathVariables.type")
                )
                .channel(MessageChannels.executor(Executors.newFixedThreadPool(10)))
                .transform(source -> {
                    if (source instanceof String) {
                        return ((String) source);
                    } else if (source instanceof byte[]) {
                        return new String(((byte[]) source));
                    }
                    return source.toString();
                })
                .aggregate(aggregatorSpec -> aggregatorSpec
                        .outputProcessor(group -> group.getMessages().stream()
                                .collect(Collectors.toMap(o -> o.getHeaders().get("humio_type", String.class), o -> singletonList(o.getPayload()), SyslogbridgeApplication::mergeLists)))
                        .sendPartialResultOnExpiry(true)
                        .expireGroupsUponCompletion(true)
                        .correlationStrategy(message -> message.getHeaders().get("humio_dataspace") + ":" + message.getHeaders().get("humio_ingesttoken"))
                        .groupTimeout(humioConfig.getGroupTimeout())
                        .releaseStrategy(new MessageCountReleaseStrategy(humioConfig.getMaxEvents()))
                )
                .transform(new GenericTransformer<Message<Map<String, List<String>>>, Message<List<HumioMessages>>>() {
                    @Override
                    public Message<List<HumioMessages>> transform(Message<Map<String, List<String>>> source) {
                        return MessageBuilder
                                .withPayload(source.getPayload().entrySet().stream()
                                        .map(entry -> HumioMessages.builder()
                                                .type(entry.getKey())
                                                .messages(entry.getValue())
                                                .build()
                                        ).collect(Collectors.toList())
                                )
                                .copyHeaders(source.getHeaders())
                                .build();
                    }
                })
                .channel(MessageChannels.executor(Executors.newFixedThreadPool(10)))
                .<List<HumioMessages>>log(LoggingHandler.Level.INFO, message -> "sending count=" + message.getPayload().stream().mapToLong(humioMessages -> humioMessages.getMessages().size()).sum() + " messages to dataspace=" + message.getHeaders().get("humio_dataspace", String.class))
                .transform(Transformers.toJson("application/json"))
                .enrichHeaders(spec -> spec.headerFunction("Authorization", message -> "Bearer " + message.getHeaders().get("humio_ingesttoken")))
                .handle(
                        Http.outboundChannelAdapter(humioConfig.getUrlPrefix() + "/api/v1/dataspaces/{dataspace}/ingest-messages")
                                .httpMethod(HttpMethod.POST)
                                .uriVariable("dataspace", message -> message.getHeaders().get("humio_dataspace")),
                        spec -> spec.advice(humioHttpClientAdvice)
                )
                .get();
    }

    @Bean
    public Advice humioHttpClientAdvice() {
        return new RequestHandlerRetryAdvice();
    }

    private static <T> List<T> mergeLists(List<T> left, List<T> right) {
        List<T> result = new ArrayList<>(left);
        result.addAll(right);
        return result;
    }

}
