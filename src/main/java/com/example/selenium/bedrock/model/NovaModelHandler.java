package com.example.selenium.bedrock.model;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.ResponseStream;

public class NovaModelHandler implements ModelHandler {

    private static Logger logger = LogManager.getLogger(NovaModelHandler.class);

    @Override
    public JSONObject createPayload(String prompt, Integer maxTokens, Double temperature) {
        JSONObject requestObject =  new JSONObject()
            .put("schemaVersion", "messages-v1")
            .put("inferenceConfig", new JSONObject()
                .put("max_new_tokens", 1000)
                .put("temperature", temperature))
                // .put("top_p", 0.1d))
            .append("messages", new JSONObject()
                    .put("role", "user")
                    .append("content", new JSONObject()
                        .put("text", prompt)));
        return requestObject;
    }

    @Override
    public JSONObject createPayload(String prompt, File imageLocation, Integer maxTokens, Double temperature) {

        String imageBase64 = ModelHandler.encodeImageToBase64(imageLocation);
        JSONObject requestObject =  new JSONObject()
            .put("schemaVersion", "messages-v1")
            .put("inferenceConfig", new JSONObject()
                .put("max_new_tokens", 1000)
                .put("temperature", temperature))
            .append("messages", new JSONObject()
                    .put("role", "user")
                    .append("content", new JSONObject()
                        .put("text", prompt))
                    .append("content", new JSONObject()
                        .put("image", new JSONObject()
                            .put("format", "png")
                            .put("source", new JSONObject()
                                .put("bytes", imageBase64)))));
        return requestObject;
    }

    @Override
    public InvokeModelWithResponseStreamResponseHandler createResponseStreamHandler(JSONObject structuredResponse) {
                AtomicReference<String> completeMessage = new AtomicReference<>("");
    
        Consumer<ResponseStream> responseStreamHandler = event -> event.accept(InvokeModelWithResponseStreamResponseHandler.Visitor.builder()
                .onChunk(c -> {
                    try {
                        // Decode the chunk
                        var chunk = new JSONObject(c.bytes().asUtf8String());
                        
                        // Nova models use contentBlockDelta for streaming responses
                        var contentBlockDelta = chunk.optJSONObject("contentBlockDelta");
                        if (contentBlockDelta != null) {
                            // Extract the text from the delta
                            var delta = contentBlockDelta.optJSONObject("delta");
                            if (delta != null) {
                                var text = delta.optString("text", "");
                                // Print the text fragment to the console if debug is enabled
                                if (logger.isDebugEnabled()) {
                                    logger.debug(text);
                                }
                                // Append it to the complete message
                                completeMessage.getAndUpdate(current -> current + text);
                            }
                        }
    
                        // Handle completion metrics
                        var metrics = chunk.optJSONObject("amazon-bedrock-invocationMetrics");
                        if (metrics != null) {
                            structuredResponse.put("metrics", new JSONObject()
                                    .put("inputTokenCount", metrics.optString("inputTokenCount"))
                                    .put("outputTokenCount", metrics.optString("outputTokenCount"))
                                    .put("firstByteLatency", metrics.optString("firstByteLatency"))
                                    .put("invocationLatency", metrics.optString("invocationLatency")));
                        }
    
                        // Handle stop reason if present
                        if (chunk.has("stopReason")) {
                            structuredResponse.put("stop_reason", chunk.getString("stopReason"));
                        }
    
                    } catch (Exception e) {
                        logger.error("Error processing chunk: " + e.getMessage(), e);
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