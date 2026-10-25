package com.supportflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiHttpTest {
    @Test void liveAdapterSendsRedactedInputAndValidatesStructuredResponse() throws Exception {
        var mapper=new ObjectMapper(); var received=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat",exchange->{
            received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String content="{\"category\":\"TECHNICAL\",\"summary\":\"API fails\",\"draft\":\"Please share a request ID.\"}";
            byte[] body=mapper.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content)))));
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var ai=new AiAssistant(mapper,"http://127.0.0.1:"+server.getAddress().getPort()+"/chat","fake-test-key","test-model");
            var t=new Ticket(); t.subject="API fails"; t.description="Contact person@example.com password=supersecret";
            var result=ai.analyze(t,List.of(),List.of());
            assertThat(result.category()).isEqualTo(Category.TECHNICAL); assertThat(result.source()).isEqualTo("LIVE");
            assertThat(received.get()).doesNotContain("person@example.com","supersecret");
            assertThat(mapper.readTree(received.get()).path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        } finally { server.stop(0); }
    }
    @Test void providerErrorsAreSafeToRetry() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat",exchange->{ exchange.sendResponseHeaders(429,-1); exchange.close(); }); server.start();
        try {
            var ai=new AiAssistant(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort()+"/chat","fake-test-key","test-model");
            var t=new Ticket(); t.subject="Issue"; t.description="Details";
            assertThatThrownBy(()->ai.analyze(t,List.of(),List.of())).isInstanceOf(IllegalStateException.class).hasMessage("AI provider HTTP 429");
        } finally { server.stop(0); }
    }
}
