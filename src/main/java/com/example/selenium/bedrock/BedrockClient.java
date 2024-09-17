package com.example.selenium.bedrock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.ResponseStream;

public class BedrockClient implements BedrockService {

    private static Logger logger    =   LogManager.getLogger(BedrockClient.class);

    @SuppressWarnings("unused")
    private static final String CLAUDE_SONNET = "anthropic.claude-3-sonnet-20240229-v1:0";
    private static final String CLAUDE_SONNET_3_5 = "anthropic.claude-3-5-sonnet-20240620-v1:0";

    @SuppressWarnings("unused")
    private static final String CLAUDE_HAIKU = "anthropic.claude-3-haiku-20240307-v1:0";

    private static final String DEFAULT_MODEL = CLAUDE_SONNET_3_5;

    private BedrockRuntimeAsyncClient client = null;

    private  BedrockClientConfig config    =   null;

    public BedrockClient() {

        this(BedrockClientConfig.builder().maxTokens(200000).modelName(BedrockClient.DEFAULT_MODEL).build());
        logger.info("Using LLM: "+BedrockClient.DEFAULT_MODEL);
    }

    public BedrockClient(BedrockClientConfig config) {
        if(  config == null){
            throw new IllegalArgumentException("apiKey and config cannot be null");
        }
        this.config = config;

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
        
        logger.info("Invoking LLM "+BedrockClient.DEFAULT_MODEL);
        JSONObject messagesApiResponse = invokeModelWithResponseStream(prompt);
        return messagesApiResponse.toString(2);
    }

    public String invokeWithImage(String prompt, File imageLocation){

        logger.info("Invoking LLM "+BedrockClient.DEFAULT_MODEL+" with image "+imageLocation.toPath().toString());
        JSONObject messagesApiResponse = invokeModelWithResponseStream(prompt, imageLocation);
        return messagesApiResponse.toString(2);
    }

    /**
     * Invokes Anthropic Claude 3 Haiku and processes the response stream.
     *
     * @param prompt The prompt for the model to complete.
     * @return A JSON object containing the complete response along with some metadata.
     */
    private JSONObject invokeModelWithResponseStream(String prompt, File imageLocation) {

        //Base64 of image
        String imageBase64 = encodeImageToBase64(imageLocation);

        String modelId = config.getModelName();

        Double temperature = 0.25d;
        if( DEFAULT_MODEL.equals( BedrockClient.CLAUDE_SONNET_3_5 )){
            temperature = 0.15d;
        }

        // Prepare the JSON payload for the Messages API request
        var payload = new JSONObject()
                .put("anthropic_version", "bedrock-2023-05-31")
                .put("max_tokens", config.getMaxTokens())
                .put("temperature", temperature)
                .append("messages", new JSONObject()
                        .put("role", "user")
                        .append("content", new JSONObject()                                
                                        .put("type", "text")
                                        .put("text", prompt))
                        .append("content", new JSONObject()
                                        .put("type", "image")
                                        .put("source", new JSONObject()
                                                .put("type", "base64")
                                                .put("media_type", "image/png")
                                                .put("data", imageBase64)))
                        );

        // logger.info("Payload:" +payload.toString(2));           
        // Create the request object using the payload and the model ID
        var request = InvokeModelWithResponseStreamRequest.builder()
                .contentType("application/json")
                .body(SdkBytes.fromUtf8String(payload.toString()))
                .modelId(modelId)
                .build();

        // Create a handler to print the stream in real-time and add metadata to a response object
        JSONObject structuredResponse = new JSONObject();
        var handler = createMessagesApiResponseStreamHandler(structuredResponse);

        // Invoke the model with the request payload and the response stream handler
        client.invokeModelWithResponseStream(request, handler).join();

        return structuredResponse;
    }    

    public static String encodeImageToBase64(File file) {

        byte[] bytes;
        try (FileInputStream fileInputStream = new FileInputStream(file)){
            bytes = new byte[(int) file.length()];
            fileInputStream.read(bytes);
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Could not encode image to Base64. File: "+file.getAbsolutePath(), e);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }    


    /**
     * Invokes Anthropic Claude 3 Haiku and processes the response stream.
     *
     * @param prompt The prompt for the model to complete.
     * @return A JSON object containing the complete response along with some metadata.
     */
    private JSONObject invokeModelWithResponseStream(String prompt) {

        String modelId = config.getModelName();

        // Prepare the JSON payload for the Messages API request
        var payload = new JSONObject()
                .put("anthropic_version", "bedrock-2023-05-31")
                .put("max_tokens", config.getMaxTokens())
                .put("temperature", 0.2)
                .append("messages", new JSONObject()
                        .put("role", "user")
                        .append("content", new JSONObject()
                                .put("type", "text")
                                .put("text", prompt)
                        ));

        // Create the request object using the payload and the model ID
        var request = InvokeModelWithResponseStreamRequest.builder()
                .contentType("application/json")
                .body(SdkBytes.fromUtf8String(payload.toString()))
                .modelId(modelId)
                .build();

        // Create a handler to print the stream in real-time and add metadata to a response object
        JSONObject structuredResponse = new JSONObject();
        var handler = createMessagesApiResponseStreamHandler(structuredResponse);

        // Invoke the model with the request payload and the response stream handler
        client.invokeModelWithResponseStream(request, handler).join();

        return structuredResponse;
    }

    private static InvokeModelWithResponseStreamResponseHandler createMessagesApiResponseStreamHandler(JSONObject structuredResponse) {
        AtomicReference<String> completeMessage = new AtomicReference<>("");

        Consumer<ResponseStream> responseStreamHandler = event -> event.accept(InvokeModelWithResponseStreamResponseHandler.Visitor.builder()
                .onChunk(c -> {
                    // Decode the chunk
                    var chunk = new JSONObject(c.bytes().asUtf8String());

                    // The Messages API returns different types:
                    var chunkType = chunk.getString("type");
                    if ("message_start".equals(chunkType)) {
                        // The first chunk contains information about the message role
                        String role = chunk.optJSONObject("message").optString("role");
                        structuredResponse.put("role", role);

                    } else if ("content_block_delta".equals(chunkType)) {
                        // These chunks contain the text fragments
                        var text = chunk.optJSONObject("delta").optString("text");
                        // Print the text fragment to the console ...
                        if( logger.isDebugEnabled() )
                            logger.debug(text);
                        // ... and append it to the complete message
                        completeMessage.getAndUpdate(current -> current + text);

                    } else if ("message_delta".equals(chunkType)) {
                        // This chunk contains the stop reason
                        var stopReason = chunk.optJSONObject("delta").optString("stop_reason");
                        structuredResponse.put("stop_reason", stopReason);

                    } else if ("message_stop".equals(chunkType)) {
                        // The last chunk contains the metrics
                        JSONObject metrics = chunk.optJSONObject("amazon-bedrock-invocationMetrics");
                        structuredResponse.put("metrics", new JSONObject()
                                .put("inputTokenCount", metrics.optString("inputTokenCount"))
                                .put("outputTokenCount", metrics.optString("outputTokenCount"))
                                .put("firstByteLatency", metrics.optString("firstByteLatency"))
                                .put("invocationLatency", metrics.optString("invocationLatency")));
                    }
                })
                .build());

        return InvokeModelWithResponseStreamResponseHandler.builder()
                .onEventStream(stream -> stream.subscribe(responseStreamHandler))
                .onComplete(() ->
                        // Add the complete message to the response object
                        structuredResponse.append("content", new JSONObject()
                                .put("type", "text")
                                .put("text", completeMessage.get())))
                .build();
    }


    public static class BedrockClientConfig {

        private int maxTokens;
        private String modelName;
        
    
        private BedrockClientConfig() {
        }

        private BedrockClientConfig(int maxTokens, String modelName) {
            this.maxTokens = maxTokens;
            this.modelName = modelName;
        }

        //getters
        public int getMaxTokens() {
            return maxTokens;
        }
       public String getModelName() {
           return modelName;
       }

        private static class BedrockClientConfigBuilder {
            private int maxTokens;
            private String modelName;

            public BedrockClientConfigBuilder() {}
            public BedrockClientConfigBuilder maxTokens(int maxTokens) {
                this.maxTokens = maxTokens;
                return this;
            }
            public BedrockClientConfigBuilder modelName(String modelName) {
               this.modelName = modelName;
                return this;
            }
            public BedrockClientConfig build() {
                return new BedrockClientConfig(this.maxTokens, this.modelName);
            }
        }
        // Getters and setters for configuration properties
        // builder method to create a BedrockClientConfig object
        public static BedrockClientConfigBuilder builder() {
            return new BedrockClientConfig.BedrockClientConfigBuilder();
        }
    }    
}
