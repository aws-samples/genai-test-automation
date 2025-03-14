package com.example.selenium.bedrock.model;

import com.example.selenium.bedrock.BedrockClient;

public class ModelHandlerFactory {
    public static ModelHandler createModelHandler(String modelName) {
        switch (modelName.toLowerCase()) {
            case BedrockClient.CLAUDE_SONNET:
                return new AnthropicModelHandler();
            case BedrockClient.CLAUDE_SONNET_3_5:
                return new AnthropicModelHandler();
            case BedrockClient.CLAUDE_SONNET_3_5_V2:
                return new AnthropicModelHandler();
            case BedrockClient.CLAUDE_SONNET_3_7:
                return new AnthropicModelHandler();
            case BedrockClient.NOVA_PRO:
                return new NovaModelHandler();
            default:
                throw new IllegalArgumentException("Unsupported model: " + modelName);
        }
    }
}