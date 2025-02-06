package com.example.selenium.command;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.Select;

import com.example.selenium.AppInfra;
import com.example.selenium.bedrock.BedrockClient;
import com.example.selenium.html.HtmlElement;
import com.googlecode.htmlcompressor.compressor.HtmlCompressor;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

public abstract class AbstractNavigation implements Command {
    
    private static final Logger logger = LogManager.getLogger(AbstractNavigation.class);
    private HtmlCompressor compressor = new HtmlCompressor();
    protected BedrockClient service = null;
    protected WebDriver browser = null;
    protected final CommandParams params;
    protected String s3Bucket = null;
    //generate ramdom 6 character string
    protected String s3Prefix = java.util.UUID.randomUUID().toString().substring(0, 6);
    protected S3Client s3Client = null;
    protected Boolean success   =   Boolean.FALSE;

    public AbstractNavigation(CommandParams params) {
        logger.info("Executing test case: "+params.getTestCase());
        try{
        	service = new BedrockClient();
        }catch(Exception e){
        	e.printStackTrace();
            throw e;
        }
        this.params = params;
        if(params.useS3()){
            this.s3Bucket = getS3BucketName();
            s3Client = S3Client.builder().build();
        }
    }

    protected void setDriver(WebDriver browser){
        this.browser = browser;
    }

    @Override
    public Command execute() throws Exception {


        String url = params.getUrl();
        Integer delay = params.getDelay();
        Integer interactions = params.getInteractions();
        Integer loadWaitTime = params.getLoadWaitTime();
        List<String> pastActions = new ArrayList<>();
        String testCase = params.getTestCase();    

        if( browser == null ){
            // Open the web browser and navigate to the app's URL
            ChromeOptions options = new ChromeOptions();
            options.setHeadless(Boolean.TRUE);
            options.addArguments("--remote-allow-origins=*", "--window-size=2560,1440", "--no-sandbox", "--disable-dev-shm-usage");
            try{
                browser = new ChromeDriver(options);
            }catch(Exception e){
                logger.error("Error starting the browser. Either the chromedriver is not available in the path or the version is different from your browser version. Msg: "+e.getMessage() );
            }

            browser.get(url);
            Thread.sleep(loadWaitTime);
        }
        String html = null;
        String htmlCompressed   =   null;
        final List<HtmlElement> elements = new ArrayList<>();

        // Start testing
        for (int i = 0; i < interactions; i++) {
            logger.info("Available interactions: "+(interactions-i));
            try{
                Integer step = i+1;  

                FluentWait<WebDriver> wait = new FluentWait<>(browser);
                wait.withTimeout(Duration.ofMillis(loadWaitTime));
                wait.pollingEvery(Duration.ofMillis(250));
                wait.until(browser-> ((JavascriptExecutor)browser).executeScript("return document.readyState").toString().equals("complete"));
        
                elements.addAll(getHtmlElements(browser, params.setIds()));
                if( params.setIds() )
                    setIds(browser, elements);

                html = cleanHtml(browser.getPageSource());
                htmlCompressed = compressor.compress(html);
                // logger.info("HTML: "+html);
                logger.info("HTML length: "+html.length());
                logger.info("HTML COMPRESSED: "+htmlCompressed.length());
                String prompt = String.format( getPrompt(), htmlCompressed, testCase, pastActions, interactions-i, elements);

                //logger.info("Source:\n "+html);
                logger.info("Prompt Length:"+prompt.length());
                
                // screenshot();
                String response = service.invokeWithImage(prompt, screenshot());
                // String response = service.invoke(prompt);

                JSONObject text = getResponseJSON(response);

                if(text.has("status")){
                    logger.info(String.format("Test finished. Status: %s. Explanation: %s", text.getString("status"), text.getString("explanation")));   
                    //take a screenshot
                    screenshot();
                    this.success    =   text.getString("status").toLowerCase().indexOf("failure")!=-1 ? Boolean.FALSE : Boolean.TRUE;
                    break;
                }

                JSONArray actions = text.getJSONArray("actions");
                String explanation = text.getString("explanation");

                //add step information inside the JSONObject text
                text.put("step", step);
                logger.info(String.format("Step #%s. Explanation: %s", step, explanation));
                logger.info(String.format("Step actions: %s", actions));
                List<HtmlElement> click = inputData(elements, actions);

                if( click.isEmpty() ){
                    logger.info("No click action found");
                    pastActions.add(String.format("{\"step\":%s, \"actions\": %s}", step, actions));
                    elements.clear();
                    new Actions(browser).sendKeys(Keys.TAB, Keys.ENTER).perform();
                    continue;
                }
                click.stream().forEach( c->{
                    logger.info("Clicking on "+c.getId());
                    c.getElement().click();
                });
                //new Actions(browser).moveToElement(click.getElement()).click().perform();
                
                pastActions.add(String.format("{\"step\":%s, \"actions\": %s}", step, actions));
                Thread.sleep(delay);

            }catch(Exception e){
                logger.error("Clicked on something that didn't work (possibly an element that is not visible or not clickable). Will continue with the next action....");
            }
            elements.clear();
        }
        return this;
    }

    private List<HtmlElement> inputData(List<HtmlElement> elements, JSONArray actions ){

        List<HtmlElement> click = new ArrayList<>();

        for(int ii=0; ii<actions.length(); ii++){
            JSONObject action = actions.getJSONObject(ii);
            if( "input".equals(action.getString("action")) ){

                String value = action.getString("value");
                Optional<HtmlElement> element = elements.stream().filter(elem-> elem.getId().equals(action.getString("id"))).findFirst();
                if(element.isPresent()){

                    if(("select").equals(element.get().getElement().getTagName().toLowerCase())){

                        Select select = new Select( element.get().getElement()) ;
                        select.selectByValue(value);
                        Optional<WebElement> option = select.getOptions().stream().filter(o-> o.isSelected()).findFirst();
                        if(option.isPresent()){
                            option.get().sendKeys(Keys.ENTER);
                        }
                        // new Actions(browser).sendKeys(Keys.ENTER).perform();
                        try{
                            screenshot();}catch(Exception e){e.printStackTrace();}
                    }else{

                        ((JavascriptExecutor)browser).executeScript("arguments[0].focus();", element.get().getElement());
                        element.get().getElement().sendKeys(Keys.chord(Keys.CONTROL, "a"), value);
                        logger.info("Inputted value "+value+" on "+element.get().getId());      
                    }                  
                }
            }else if( "click".equals(action.getString("action")) ){
                
                Optional<HtmlElement> webElement = elements.stream().filter(e-> action.getString("id").equals(e.getId())).findFirst();
                if(webElement.isPresent()){
                    click.add(webElement.get());
                }
            }
        }
        return click;
    }

    protected static String cleanHtml(String htmlString) {

        final Document doc = Jsoup.parse(htmlString);

        // Remove all script and style elements
        Elements scripts = doc.select("script, style");
        for (Element script : scripts) {
            script.remove();
        }
        //remove script tags inside the body
        Document body = Jsoup.parseBodyFragment(doc.select("body").html());
        body.select("script").remove();

        removeComments(body);

        //remove script and head from inside the iframe
        Elements iframes = doc.select("iframe");

        // Iterate through the <iframe> elements
        for (Element iframe : iframes) {

            if(!iframe.attributes().hasKey("srcdoc")) continue;
            // Parse the content of the <iframe> as a new Document
            Document iframeDoc = Jsoup.parse(iframe.attributes().get("srcdoc"));
        
            // Select all <script> elements within the <head> section of the <iframe>
            scripts = iframeDoc.select("script");
            //remove script tags inside the body
            Document bodyIFrameDoc = Jsoup.parseBodyFragment(iframeDoc.select("body").html());
            bodyIFrameDoc.select("script").remove();
        
            // Remove the selected <script> elements
            scripts.remove();
        
            // Update the <iframe> content with the modified HTML
            iframe.attributes().put("srcdoc", iframeDoc.outerHtml());
        }        

        // Remove the div with id 'coverage'
        Element coverageDiv = doc.selectFirst("#coverage");
        if (coverageDiv != null) {
            coverageDiv.remove();
        }

        // Remove class from divs
        Elements divs = doc.select("div");
        divs.stream().forEach(div-> div.removeAttr("class"));

        // Remove class from span
        Elements spans = doc.select("span");
        spans.stream().forEach(span-> span.removeAttr("class"));

        // Remove class from ul
        Elements uls = doc.select("ul");
        uls.stream().forEach(ul-> ul.removeAttr("class"));

        //remove the attributes data- attributes from a, ul, div, span, input
        Elements elementsWithDataAttrs = doc.select("[^data-]");
        elementsWithDataAttrs.forEach(elem->{
           
           List<Attribute> attributes = elem.attributes().asList();
           attributes.stream().filter(attr->attr.getKey().indexOf("data-") != -1).forEach(attr->elem.removeAttr(attr.getKey()));
        });

        //if href starts with / we will on try to remove the extra info and update href with information inside the first / and the second /
        Elements links = doc.select("a");
        links.stream().forEach(link->{

            String href = link.attr("href");
            if(href.startsWith("/")){
                String[] parts = href.split("/");
                if(parts.length > 2){
                    link.attr("href", "/"+parts[1]+"/");
                }
            }else if(href.startsWith("https")){
                String[] parts = href.split("/");
                if(parts.length > 3){
                    link.attr("href", "https://"+parts[2]);
                }
                if( parts.length == 3){
                    if(parts[2].indexOf("?")!=-1){
                        link.attr("href", "https://"+parts[2].substring(0, parts[2].indexOf("?")));
                    }else{
                        link.attr("href", "https://"+parts[2]);
                    }
                }
            }
        });

        // Remove the alt attribute from images
        Elements images = doc.select("img");
        images.stream().forEach(image->image.removeAttr("alt"));
        images.stream().forEach(image->image.removeAttr("srcset"));        
        
        return doc.html().replaceAll("\\s+", " ");
    }

    private static void removeComments(Node node) {
        for (int i = 0; i < node.childNodeSize();) {
            Node child = node.childNode(i);
            if (child.nodeName().equals("#comment"))
                child.remove();
            else {
                removeComments(child);
                i++;
            }
        }
    }

    protected void setIds(WebDriver browser, List<HtmlElement> elements){

        elements.stream().filter(elem -> elem.isIdGenerated()).forEach(elem -> {
                
            ((JavascriptExecutor)browser).executeScript(String.format("arguments[0].id='%s';", elem.getId()), elem.getElement());
         } );  
    }

    protected List<HtmlElement> getHtmlElements(WebDriver browser, final Boolean allElements){

        List<HtmlElement> buttons   =   browser.findElements(By.xpath("//button"))
            .stream()
            .filter(e->e.isDisplayed())
            .filter(e->e.isEnabled())
            .filter(e->  allElements || (e.getAttribute("id")!=null && !"".equals(e.getAttribute("id"))))
            .map(e-> HtmlElement.builder()
                .id(e.getAttribute("id"))
                .type("clickable")
                .element(e)
                .build())
            .toList();

        List<HtmlElement> inputs = browser.findElements(By.tagName("input"))
            .stream()
            .filter(e->e.isDisplayed())
            .filter(e->e.isEnabled())
            .filter(e->  allElements || (e.getAttribute("id")!=null && !"".equals(e.getAttribute("id"))))
            .map(e-> HtmlElement.builder()
                .id(e.getAttribute("id"))
                .type("input")
                .element(e)
                .build())
            .toList();

        List<HtmlElement> anchors = browser.findElements(By.tagName("a"))
            .stream()
            .filter(e->e.isDisplayed())
            .filter(e->e.isEnabled())
            .filter(e->  allElements || (e.getAttribute("id")!=null && !"".equals(e.getAttribute("id"))))
            .map(e-> HtmlElement.builder()
                .id(e.getAttribute("id"))
                .type("clickable")
                .element(e)
                .build())
            .toList();       
            
        List<HtmlElement> textarea = browser.findElements(By.tagName("textarea"))
            .stream()
            .filter(e->e.isDisplayed())
            .filter(e->e.isEnabled())
            .filter(e->  allElements || (e.getAttribute("id")!=null && !"".equals(e.getAttribute("id"))))
            .map(e-> HtmlElement.builder()
                .id(e.getAttribute("id"))
                .type("input")
                .element(e)
                .build())
            .toList();           

        List<HtmlElement> select = browser.findElements(By.tagName("select"))
            .stream()
            .filter(e->e.isDisplayed())
            .filter(e->e.isEnabled())
            .filter(e->  allElements || (e.getAttribute("id")!=null && !"".equals(e.getAttribute("id"))))
            .map(e-> HtmlElement.builder()
                .id(e.getAttribute("id"))
                .type("input")
                .element(e)
                .build())
            .toList();              

        List<HtmlElement> clickable = browser.findElements(By.xpath("//*[not(self::button or self::a or self::input)][@onclick or contains(@onclick, 'click')]"))
            .stream()
            .filter(e->e.isDisplayed())
            .filter(e->e.isEnabled())
            .filter(e->  allElements || (e.getAttribute("id")!=null && !"".equals(e.getAttribute("id"))))
            .map(e-> HtmlElement.builder()
                .id(e.getAttribute("id"))
                .type("clickable")
                .element(e)
                .build())
            .toList(); 

        List<HtmlElement> span = browser.findElements(By.tagName("span"))
            .stream()
            .filter(e->e.isDisplayed())
            .filter(e->e.isEnabled())
            .filter(e->  allElements || (e.getAttribute("id")!=null && !"".equals(e.getAttribute("id"))))
            .map(e-> HtmlElement.builder()
                .id(e.getAttribute("id"))
                .type("clickable")
                .element(e)
                .build())
            .toList(); 
        
        final List<HtmlElement> elements = new ArrayList<>();
        elements.addAll(buttons);
        elements.addAll(inputs);
        elements.addAll(anchors);
        elements.addAll(textarea);
        elements.addAll(select);
        elements.addAll(clickable);
        elements.addAll(span);

        return elements;
    }
    
    JSONObject getResponseJSON(String response) throws Exception {

        try{
            // Parse the response to get the selected element ID and explanation
            JSONObject jsonResponse = new JSONObject(response);
            try{
                logger.info(String.format("Input Token Count: %s ", jsonResponse.getJSONObject("metrics").getString("inputTokenCount")));
                logger.info(String.format("Output Token Count: %s", jsonResponse.getJSONObject("metrics").getString("outputTokenCount")));
            }catch(Exception e){
                logger.info("Unable to get token count");
            }
            //get content object that is an array of json objects
            JSONArray content = jsonResponse.getJSONArray("content");
            //get the first element of the array
            JSONObject firstElement = content.getJSONObject(0);

            String rawResponse = firstElement.getString("text");

            rawResponse = rawResponse.replaceAll("\n", "");
            //extract JSON Object from the response
            return new JSONObject( rawResponse.substring(rawResponse.indexOf("{"), rawResponse.lastIndexOf("}")+1));
        }catch(Exception e){
            logger.info("Unable to parse response: "+response);
            throw e;
        }
    }
    
    protected File screenshot() throws IOException{
        File screenshot = ((TakesScreenshot)browser).getScreenshotAs(OutputType.FILE);
        String screenshotName = String.format("screenshot-%d.png", System.currentTimeMillis());
        //test if directory exists otherwise create it
        File directory = new File("./screenshots");
        if (!directory.exists()) {
            if(!directory.mkdirs()){
                logger.info("Unable to create directory "+directory.getName());
            }
        }
        File screenshotFile = new File("./screenshots/"+screenshotName);
        Files.copy(screenshot.toPath(), screenshotFile.toPath());
        logger.info("Screenshot saved to "+screenshotFile.toString());

        if(this.params.useS3()){

            PutObjectRequest objectRequest = PutObjectRequest.builder()
                    .bucket(this.s3Bucket)
                    .key(this.s3Prefix+"/"+screenshotName.toString())
                    .build();

            s3Client.putObject(objectRequest, RequestBody.fromFile(screenshotFile));
            logger.info("Screenshot saved to "+objectRequest.bucket()+"/"+objectRequest.key());
        }
        return screenshotFile;
    }

    protected String getPrompt(){
       return """
            Human: You are a professional tester testing web applications. You provide the output to the next step you need to execute to complete the test case. You can provide values to several inputs at once but one click action only on each step. Your actions must use actionable elements from the input. Provide the information to the next step according to the following instructions:

            1- One input is the HTML source code of the web page. You will find it inside <code></code> tags.
            2- Another input is the description of the test case you are executing. You will find it inside <testcase></testcase> tags
            3- Another input is the list of past actions that you have done so far. The first element is the first action of the test and last element is the previous action. You will find it inside <action></action> tags
            4- Another input is the number of available interactions. You will find it inside <available-interactions></available-interactions> tags.
            5- Another input is the list of elements available for you to interact with. They are of type input or clickable. You will find it inside <interact></interact> tags
            6- Your answer must always be JSON Object containing the next step or a test case completed response type. The next step object should contain a key "explanation" and a key "actions". Key "actions" is an array of JSON objects with keys "action", "id" and "value". Sometimes you need to click an element to visualize the input form. These are the examples:
            <examples>
            {"explanation":"Click on the button to submit the form","actions":[{"action":"click","id":"button1","value":"Submit"}, {"action":"input","id":"name-field","value":"John Doe"}, {"action":"input","id":"dropdown-menu","value":"Option 2"}, {"action":"input","id":"email-field","value":"johndoe@example.com"} ]}
            {"explanation":"Click on the button to submit the form","actions":[{"action":"click","id":"link-1","value":"Learn More"}]}
            {"explanation":"Click on the button to submit the form","actions": [{"action":"input","id":"name-field","value":"John Doe"}, {"action":"input","id":"dropdown-menu","value":"Option 2"}, {"action":"input","id":"email-field","value":"johndoe@example.com"}, {"action":"click","id":"link-sign-in","value":"SignIn"} ]}
            </examples>
            7- When test case is completed your answer must be a JSON object with two keys, status and explanation. Here are a few examples:
            <examples>
            {"status":"success","explanation":"<EXPLANATION>"}
            {"status":"failure","explanation":"<EXPLANATION>"}
            </examples>
            8- For test to finish successfully, your explanation must contain evidence within the source HTML code that conditions to finish the test were met. Do not finish test successfully before finding evidence within the HTML code.
            9- You can use information from the image that was rendered using the HTML code provided within <code></code>

            <code>%s</code>
            <testcase>%s The test fails if you cannot complete the action after the number of available interactions gets to 0 or if you cannot complete the action for another reason.</testcase>
            <actions>%s</actions>
            <available-interactions>%s</available-interactions>
            <interact>%s</interact>.
            
            Answer in JSON format:     
                """;   
    }// Your answer is in JSON format. Your answer contain at most only one click action. You execute at least 10 steps before failing. Your actions use elements from the input

    @Override
    public Command andThen(Command c) throws Exception {
        
        ((AbstractNavigation)c).setDriver(browser);
        return c.execute();
    }

    @Override
    public void tearDown() throws Exception {
        //release resources
        if( browser!=null){
            browser.close();
            browser.quit();
        }
    }
    
    public String getS3BucketName(){

        // Create an SsmClient
        SsmClient ssmClient = SsmClient.builder().build();
        // Get the value of a parameter from the Parameter Store
        GetParameterRequest getParameterRequest = GetParameterRequest.builder()
                                                .name("/"+AppInfra.Constants.APP_NAME+"/bucket")
                                                .build();

        GetParameterResponse getParameterResponse = ssmClient.getParameter(getParameterRequest);
        final String S3_BUCKET = getParameterResponse.parameter().value();
        logger.info("Using S3 bucket "+S3_BUCKET);
        return S3_BUCKET; 
    }

    public String getS3Prefix(){

        return this.s3Prefix;
    }

    public String status(){

        if( this.success ){
            return "SUCCEED";
        }else{
            return "FAIL";
        }
    }
}
