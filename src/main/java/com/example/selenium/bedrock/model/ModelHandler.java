package com.example.selenium.bedrock.model;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Base64;

import org.json.JSONObject;

import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamResponseHandler;

public interface ModelHandler {
    JSONObject createPayload(String prompt, Integer maxTokens, Double temperature);
    JSONObject createPayload(String prompt, File imageLocation, Integer maxTokens, Double temperature);
    InvokeModelWithResponseStreamResponseHandler createResponseStreamHandler(JSONObject structuredResponse);
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
}