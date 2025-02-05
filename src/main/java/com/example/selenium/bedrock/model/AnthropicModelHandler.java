package com.example.selenium.bedrock.model;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.ResponseStream;

public class AnthropicModelHandler implements ModelHandler {

    private static Logger logger = LogManager.getLogger(AnthropicModelHandler.class);
    @Override
    public JSONObject createPayload(String prompt, Integer maxTokens, Double temperature) {
        var payload = new JSONObject()
        .put("anthropic_version", "bedrock-2023-05-31")
        .put("max_tokens", maxTokens)
        .put("temperature", temperature)
        .append("messages", new JSONObject()
                .put("role", "user")
                .append("content", new JSONObject()
                        .put("type", "text")
                        .put("text", prompt)
                ));

        return payload;
    }

    @Override
    public JSONObject createPayload(String prompt, File imageLocation, Integer maxTokens, Double temperature) {
        JSONObject requestBody = new JSONObject()
            .put("anthropic_version", "bedrock-2023-05-31")
            .put("max_tokens", maxTokens)
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
                                            .put("data", ModelHandler.encodeImageToBase64(imageLocation))))
                    );
        return requestBody;
    }

    @Override
    public InvokeModelWithResponseStreamResponseHandler createResponseStreamHandler(JSONObject structuredResponse) {

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
}