package com.example.selenium.sqs;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.example.selenium.AppInfra;
import com.example.selenium.command.AbstractNavigation;
import com.example.selenium.command.Command;
import com.example.selenium.command.CommandParams;
import com.example.selenium.command.Navigate;
import com.example.selenium.command.SolveCaptcha;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

public class MessageHandler {

    private static final Logger logger = LogManager.getLogger(MessageHandler.class);
    private final SsmClient ssmClient;
    private final SqsClient sqsClient;
    private final String QUEUE_URL;
    private final String REPLY_QUEUE_URL;
    
    public MessageHandler(){

                // Create an SsmClient
        ssmClient = SsmClient.builder().build();

        // Get the value of a parameter from the Parameter Store
        GetParameterRequest getParameterRequest = GetParameterRequest.builder()
            .name("/"+AppInfra.Constants.APP_NAME+"/queue-url")
            .build();

        GetParameterRequest getParameterRequestReplyQueue = GetParameterRequest.builder()
            .name("/"+AppInfra.Constants.APP_NAME+"/queue-reply-url")
            .build();                                                

        GetParameterResponse getParameterResponse = ssmClient.getParameter(getParameterRequest);
        QUEUE_URL = getParameterResponse.parameter().value();

        GetParameterResponse getParameterReplyQueue = ssmClient.getParameter(getParameterRequestReplyQueue);
        REPLY_QUEUE_URL = getParameterReplyQueue.parameter().value();

        logger.info("Will reply to queue "+REPLY_QUEUE_URL);
        logger.info("Reading from queue "+QUEUE_URL);
        sqsClient = SqsClient.builder().build();

    }

    public void processMessages() {

        while(true){


            List<Message> messages = null;
            try{
                messages = readSqs(sqsClient, QUEUE_URL);
            }catch(Exception e){
                logger.error("Error reading from queue "+QUEUE_URL+". Will try again in 3 seconds. Msg: "+e.getMessage());
                try {
                    Thread.sleep(3000);
                }catch (InterruptedException e1) {
                    e1.printStackTrace();
                }
                continue;
            }
            logger.info("Found messages: " + messages);

            for (Message message : messages) {

                logger.info("Message body: " + message.body());
                Command command = null;
                // Parse the response to get the selected element ID and explanation
                try{
                    JSONObject jsonResponse = new JSONObject(message.body());
                    String url = null;
                    String id = null;
                    Boolean setIds = Boolean.FALSE;
                    JSONArray testCases = null;
                    try{
                        url = jsonResponse.getString("url");
                        id = jsonResponse.getString("id");
                        testCases = jsonResponse.getJSONArray("testCases");
                    }catch(JSONException e){
                        logger.info("Message body needs to include id, url and testCases[]. Discarding message.");
                        deleteSqsMessage(sqsClient, message.receiptHandle(), QUEUE_URL);
                        continue;
                    }
                    try{
                        setIds = jsonResponse.getBoolean("setIds");
                    }catch(JSONException e){}   

                    logger.info("URL: " + url);
                    logger.info("Set IDs: " + setIds);

                    for(int i=0; i<testCases.length(); i++) {
                        String testCase = testCases.getString(i);
                        logger.info("Test case: " + testCase);
                        if( "solve-captcha".equals(testCase.toString().trim())){
                            logger.info("Solving captcha: " + testCase);
                            command = new SolveCaptcha(CommandParams.builder()
                                    .url(url)
                                    .testCase("""
                                            Human: Answer the following captcha. Your answer should output ONLY the value of the captcha
                                            Assitant: The answer to the captcha is 
                                    """)
                                .build());
                        }else{
                            if( i == 0 ){
                                logger.info("Executing Navigate command");
                                command = new Navigate(CommandParams.getDefaultUseS3(url, testCase.toString(), setIds));
                            }else{
                                if( command != null){
                                    logger.info("Chaining command. AndThen...");
                                    command.andThen( new Navigate(CommandParams.getDefaultUseS3(url, testCase.toString(), setIds)));
                                }else{
                                    logger.error("Should never have reached this line");
                                }
                            }
                        }
                        if(i == 0){
                            if( command != null ){
                                command.execute();
                            }else{
                                logger.error("Should never have reached this line 2");
                            }
                        }
                    }
                    if( command != null ){

                        putSqs(sqsClient, REPLY_QUEUE_URL, String.format("""
                            {
                                "status": "%s",
                                "id": "%s",
                                "s3Prefix": "%s"
                            }
                        """, command.status(), id,((AbstractNavigation)command).getS3BucketName() +"/"+ ((AbstractNavigation)command).getS3Prefix()));
                    }else{
                        logger.error("Did not execute any test cases");
                    }

                    deleteSqsMessage(sqsClient, message.receiptHandle(), QUEUE_URL);
                    logger.info("Processed and deleted message with receipt handle: " + message.receiptHandle());
        
                }catch(Exception e){
                    if( command!= null ){
                        try{
                            command.tearDown();
                        }catch(Exception ex){
                            logger.error("Error tearing down: "+ex.getMessage(), ex);
                        }
                    }
                    logger.error("Error parsing JSON message: "+e.getMessage(), e);
                }
            }
        }
    }


    private List<Message> readSqs(SqsClient sqsClient, final String QUEUE_URL) {


        ReceiveMessageRequest receiveMessageRequest = ReceiveMessageRequest.builder()
                .queueUrl(QUEUE_URL)
                .maxNumberOfMessages(1)                
                // .waitTimeSeconds(20)
                .attributeNames(List.of(QueueAttributeName.CREATED_TIMESTAMP))
                .messageAttributeNames("All")
                .build();
                
        ReceiveMessageResponse receiveMessageResponse = sqsClient.receiveMessage(receiveMessageRequest);
        logger.info("SQS responded with " + receiveMessageResponse);

        return receiveMessageResponse.messages();
    }

    private Boolean putSqs(SqsClient sqsClient, final String QUEUE_URL, final String payload){

        try{
            logger.info("Sending messsage to queue "+QUEUE_URL+" with payload "+payload);
            sqsClient.sendMessage( builder->{
                builder.queueUrl(QUEUE_URL);
                builder.messageBody(payload);
            });
        }catch(Exception e){
            logger.error("Error sending message to SQS: "+e.getMessage(), e);
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }

    private void deleteSqsMessage(SqsClient sqsClient, String receiptHandle, final String QUEUE_URL) {
        logger.info("Deleting message " + receiptHandle);

        DeleteMessageRequest deleteMessageRequest = DeleteMessageRequest.builder()
                .queueUrl(QUEUE_URL)
                .receiptHandle(receiptHandle)
                .build();

        sqsClient.deleteMessage(deleteMessageRequest);
    }    
}
