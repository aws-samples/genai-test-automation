package com.example.selenium;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.example.selenium.command.CommandParams;
import com.example.selenium.command.Navigate;
import com.example.selenium.command.SolveCaptcha;
import com.example.selenium.sqs.MessageHandler;


/**
 * @author Luiz Decaro
 *
 */
public class App {

    private static final Logger logger = LogManager.getLogger(App.class);

    public static void main( String[] args ) throws Exception{

        try{
            logger.info("Starting tests...");

            checkDriver();

            String useSqs = System.getProperty("test-automation.use.sqs");
            if( useSqs != null && useSqs.equals("true")){

                logger.info("Reading from SQS");
                MessageHandler sqsHandler = new MessageHandler();
                sqsHandler.processMessages();

            }else{

                runTestAmazonCart();
                
            }
        }catch(Exception e){
            e.printStackTrace();
            throw e;
        }
    }


    private static void runTestAmazonCart() throws Exception{

        try {
            new SolveCaptcha(CommandParams.builder()
                .url("https://www.amazon.com/")
                .testCase("""
                        Human: Answer the following captcha. Your answer should output ONLY the value of the captcha
                        Assistant: The answer to the captcha is 
                """)
                .build())
            .execute()
            .andThen(new Navigate(CommandParams.builder()
                .url("https://www.amazon.com/")
                .testCase("""
                        You are testing the amazon.com web application. Your test case is to add to cart the most expensive soccer ball. The test case finishes when the soccer ball is visible within the cart. You should monitor the number of items in the cart.
                """)
                .build()))
            .tearDown();
            
        } catch (Exception e) {
            logger.error("Error running the test: "+e.getMessage(), e);
        }     
    }

    private static boolean isBinaryAvailable(String binaryName) {
        try {
            // Try to execute the command to find the binary
            Process process = Runtime.getRuntime().exec("which " + binaryName);
            //print the output of the command
            logger.info( new String(process.getInputStream().readAllBytes()));
            int exitCode = process.waitFor();

            // If the exit code is 0, the binary was found
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            // If any exception occurs, assume the binary is not available
            return false;
        }
    }

    private static boolean isWindows() {
        String osName = System.getProperty("os.name");
        return osName.startsWith("Windows");
    }
    
    private static void checkDriver(){

        String chromeDriver = null;
        if( isWindows() ){
            chromeDriver = "chromedriver.exe";
        }else{
            chromeDriver = "chromedriver";
        }

        if( ! isBinaryAvailable(chromeDriver) ){

            logger.info("Chrome driver is not available in the system PATH. Will check if we have a system property set with the correct location (webdriver.chrome.driver)");
            if( System.getProperty("webdriver.chrome.driver") == null ){

                logger.info("Chrome driver is not set as a system property (webdriver.chrome.driver). Setting a default location for it... ");
                System.setProperty("webdriver.chrome.driver", "/usr/bin");
                logger.info("Set system property webdriver.chrome.driver with a default location of your chrome driver. Current set location is:  "+System.getProperty("webdriver.chrome.driver"));
            }else{

                logger.info("Chrome driver is available a system property (webdriver.chrome.driver) in the following location: "+System.getProperty("webdriver.chrome.driver"));
            }
        }else{
            logger.info("Chrome driver is available in the system PATH");
        }
    }
}
