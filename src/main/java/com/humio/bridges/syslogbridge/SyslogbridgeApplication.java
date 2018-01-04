package com.humio.bridges.syslogbridge;

import com.humio.bridges.syslogbridge.model.HumioConfig;
import com.humio.bridges.syslogbridge.model.HumioMessages;
import org.aopalliance.aop.Advice;
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
import org.springframework.integration.handler.advice.RequestHandlerRetryAdvice;
import org.springframework.integration.http.dsl.Http;
import org.springframework.integration.transformer.GenericTransformer;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import java.util.List;
import java.util.concurrent.Executors;

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
                .aggregate(aggregatorSpec -> aggregatorSpec
                        .sendPartialResultOnExpiry(true)
                        .expireGroupsUponCompletion(true)
                        .correlationStrategy(message -> message.getHeaders().get("humio_dataspace") + ":" + message.getHeaders().get("humio_type") + ":" + message.getHeaders().get("humio_ingesttoken"))
                        .groupTimeout(1000)
                        .releaseStrategy(new MessageCountReleaseStrategy(500))
                )
                .transform(new GenericTransformer<Message<List<String>>, Message<List<HumioMessages>>>() {
                    @Override
                    public Message<List<HumioMessages>> transform(Message<List<String>> source) {
                        return MessageBuilder
                                .withPayload(singletonList(HumioMessages.builder()
                                        .type(source.getHeaders().get("humio_type", String.class))
                                        .messages(source.getPayload())
                                        .build()))
                                .copyHeaders(source.getHeaders())
                                .build();
                    }
                })
                .channel(MessageChannels.executor(Executors.newFixedThreadPool(10)))
                .transform(Transformers.toJson("application/json"))
                .enrichHeaders(spec -> spec.headerFunction("Authorization", message -> "Bearer " + message.getHeaders().get("humio_ingesttoken")))
                .handle(
                        Http.outboundGateway(humioConfig.getUrlPrefix() + "/api/v1/dataspaces/{dataspace}/ingest-messages")
                                .httpMethod(HttpMethod.POST)
                                .uriVariable("dataspace", message -> message.getHeaders().get("humio_dataspace")),
                        spec -> spec.advice(humioHttpClientAdvice)
                )
                .log()
                .get();
    }

    @Bean
    public Advice humioHttpClientAdvice() {
        return new RequestHandlerRetryAdvice();
    }


}
