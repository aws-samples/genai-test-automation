package com.example.selenium.bedrock;

import java.io.File;
import java.time.Duration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import com.example.selenium.bedrock.model.ModelHandler;
import com.example.selenium.bedrock.model.ModelHandlerFactory;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamRequest;

public class BedrockClient implements BedrockService {

    private static final Logger logger = LogManager.getLogger(BedrockClient.class);

    public static final String CLAUDE_SONNET = "anthropic.claude-3-sonnet-20240229-v1:0";
    public static final String CLAUDE_SONNET_3_5 = "anthropic.claude-3-5-sonnet-20240620-v1:0";
    public static final String CLAUDE_SONNET_3_5_V2 = "us.anthropic.claude-3-5-sonnet-20241022-v2:0";
    public static final String CLAUDE_SONNET_3_7 = "us.anthropic.claude-3-7-sonnet-20250219-v1:0";
    public static final String NOVA_PRO = "amazon.nova-pro-v1:0";

    @SuppressWarnings("unused")
    private static final String CLAUDE_HAIKU = "anthropic.claude-3-haiku-20240307-v1:0";

    public static final String DEFAULT_MODEL = CLAUDE_SONNET_3_5_V2;
    
    private BedrockRuntimeAsyncClient client;
    private BedrockClientConfig config;
    private ModelHandler modelHandler;

    public BedrockClient() {
        this(BedrockClientConfig.builder().build());
    }

    public BedrockClient(BedrockClientConfig config) {
        initialize(config);
    }

    private void initialize(BedrockClientConfig config) {
        this.config = config;
        this.client = BedrockRuntimeAsyncClient.create();
        logger.info("Using LLM: "+config.getModelId());
        this.modelHandler = ModelHandlerFactory.createModelHandler(config.getModelId());
        
                // Configure the Netty NIO HTTP client with timeout settings
        SdkAsyncHttpClient httpClient = NettyNioAsyncHttpClient.builder()
                .readTimeout(Duration.ofSeconds(300))  // Socket read timeout
                .writeTimeout(Duration.ofSeconds(300))  // Socket write timeout
                .connectionAcquisitionTimeout(Duration.ofSeconds(10))  // Connection acquisition timeout
                .connectionTimeout(Duration.ofSeconds(500))  // Connection timeout
                .connectionMaxIdleTime(Duration.ofSeconds(300))  // Maximum idle time for a connection
                .tlsNegotiationTimeout(Duration.ofSeconds(350))
                .build();

        client = BedrockRuntimeAsyncClient.builder()
                .httpClient(httpClient)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.US_EAST_1)
                .build();   
    }

    @Override
    public String invoke(String prompt) {
        logger.info("Invoking LLM "+config.getModelId());
        JSONObject response = invokeModelWithResponseStream(prompt);
        return extractTextFromResponse(response);
    }

    public String invokeWithImage(String prompt, File imageLocation) {
        logger.info("Invoking LLM "+config.getModelId()+" with image "+imageLocation.toPath().toString());
        JSONObject response = invokeModelWithResponseStream(prompt, imageLocation);
        return extractTextFromResponse(response);
    }

    private JSONObject invokeModelWithResponseStream(String prompt) {

        JSONObject payload = modelHandler.createPayload(prompt, config.getMaxTokens(), config.getTemperature());

        var request = InvokeModelWithResponseStreamRequest.builder()
                .contentType("application/json")
                .body(SdkBytes.fromUtf8String(payload.toString()))
                .modelId(config.getModelId())
                .build();

        JSONObject structuredResponse = new JSONObject();
        var handler = modelHandler.createResponseStreamHandler(structuredResponse);
        client.invokeModelWithResponseStream(request, handler).join();

        return structuredResponse;
    }

    private JSONObject invokeModelWithResponseStream(String prompt, File imageLocation) {

        JSONObject requestBody = modelHandler.createPayload(prompt, imageLocation, config.getMaxTokens(), config.getTemperature());
        
        var request = InvokeModelWithResponseStreamRequest.builder()
                .contentType("application/json")
                .body(SdkBytes.fromUtf8String(requestBody.toString()))
                .modelId(config.getModelId())
                .build();

        JSONObject structuredResponse = new JSONObject();
        var handler = modelHandler.createResponseStreamHandler(structuredResponse);
        client.invokeModelWithResponseStream(request, handler).join();

        return structuredResponse;
    }

    private String extractTextFromResponse(JSONObject response) {
        if (response.has("completion")) {
            return response.getString("completion");
        }
        return response.toString();
    }

    public static class BedrockClientConfig {
        private final int maxTokens;
        private final String modelId;
        private final Double temperature;

        private BedrockClientConfig() {
            this(300000, BedrockClient.DEFAULT_MODEL, 0.15d);
        }

        private BedrockClientConfig(int maxTokens, String modelId, Double temperature) {
            this.maxTokens = maxTokens;
            this.modelId = modelId;
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public String getModelId() {
            return modelId;
        }

        public Double getTemperature() {
            return temperature;
        }

        private static class BedrockClientConfigBuilder {
            private int maxTokens = 300000;
            private String modelId = BedrockClient.DEFAULT_MODEL;
            private Double temperature = 0.15d;

            public BedrockClientConfigBuilder() {}

            public BedrockClientConfigBuilder maxTokens(int maxTokens) {
                this.maxTokens = maxTokens;
                return this;
            }

            public BedrockClientConfigBuilder modelId(String modelId) {
                this.modelId = modelId;
                return this;
            }

            public BedrockClientConfigBuilder temperature(Double temperature){
                this.temperature = temperature;
                return this;
            }

            public BedrockClientConfig build() {
                return new BedrockClientConfig(maxTokens, modelId, temperature);
            }
        }

        public static BedrockClientConfigBuilder builder() {
            return new BedrockClientConfigBuilder();
        }
    }
}