package com.example.selenium.command;

import java.io.File;
import java.time.Duration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.FluentWait;

public class SolveCaptcha  extends AbstractNavigation {

    private static final Logger logger = LogManager.getLogger(SolveCaptcha.class);

    public SolveCaptcha(CommandParams params){
        super(params);
    }

    @Override
    public Command execute() throws Exception {
       
        String url = params.getUrl();
        Integer loadWaitTime = params.getLoadWaitTime();        
        String testCase = params.getTestCase();

        // Open the web browser and navigate to the app's URL
        ChromeOptions options = new ChromeOptions();
        options.setHeadless(Boolean.FALSE);
        options.addArguments("--remote-allow-origins=*");  
        options.addArguments("--window-size=2560,1440", "--no-sandbox", "--disable-dev-shm-usage");  

        try{
            browser = new ChromeDriver(options);
        }catch(Exception e){
            logger.error("Error starting the browser. Either the chromedriver is not available in the path or the version is different from your browser version. Msg: "+e.getMessage() );
            throw e;
        }
        browser.get(url);
        Thread.sleep(loadWaitTime);
        while(true){
            //wait for it to finish loading.
            FluentWait<WebDriver> wait = new FluentWait<>(browser);
            wait.withTimeout(Duration.ofMillis(loadWaitTime));
            wait.pollingEvery(Duration.ofMillis(250));
            wait.until(browser-> ((JavascriptExecutor)browser).executeScript("return document.readyState").toString().equals("complete"));

            File screenshot = screenshot();
            String captchaResult = service.invokeWithImage(testCase, screenshot);
            String cleanResult = parseResponse(captchaResult);
            logger.info("Captcha result: "+cleanResult);
            new Actions(browser).sendKeys(Keys.TAB).perform();
            new Actions(browser).sendKeys(cleanResult).perform();
            new Actions(browser).sendKeys(Keys.ENTER).perform();
            if (!missedCaptcha(browser.getPageSource())){
                super.success = true;
                break;
            }
        }
        logger.info("Captcha solved");
        return this;
    }
  
    private Boolean missedCaptcha(String htmlPage){
        return htmlPage.toLowerCase().contains("type the characters you see");
    }

    private String parseResponse(String response){
        
        // Parse the response to get the selected element ID and explanation
        JSONObject jsonResponse = new JSONObject(response);
        //get content object that is an array of json objects
        JSONArray content = jsonResponse.getJSONArray("content");
        //get the first element of the array
        JSONObject firstElement = content.getJSONObject(0);

        String rawResponse = firstElement.getString("text");

        return rawResponse.replaceAll("\n", "");    }
}
