package com.example.selenium.html;

import java.util.UUID;

import org.openqa.selenium.WebElement;

public class HtmlElement {

    private final String type;
    private final String id;
    private final WebElement element;
    private Boolean idGenerated = Boolean.FALSE;


    public HtmlElement(String type, String id, WebElement element) {

        this.type = type;
        if( id == null || "".equals(id)){
            String tempId = UUID.randomUUID().toString();
            this.id = tempId.substring(0, tempId.indexOf("-"));
            idGenerated = Boolean.TRUE;
        }else{
            this.id = id;
        }
        this.element = element;
    }
   
    public String getType() {
        return type;
    }
    public String getId() {
        return id;
    }
    public WebElement getElement() {
        return element;
    }

    public Boolean isIdGenerated() {
        return idGenerated;
    }

    public String toString() {
        return String.format("[type=%s,id=%s]", type, id);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String type;
        private String id;
        private WebElement element;

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder element(WebElement element) {
            this.element = element;
            return this;
        }

        public HtmlElement build() {
            return new HtmlElement(type, id, element);
        }
    }
}
