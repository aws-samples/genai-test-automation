package com.example.selenium.command;

public class CommandParams {
    
    private String url  =   null;
    private Integer delay = 300;
    private Integer interactions = 100;
    private Integer loadWaitTime = 5000;
    private String testCase = "";
    private Boolean setIds = Boolean.FALSE;
    private Boolean useS3 = Boolean.FALSE;
    private Boolean headless = Boolean.TRUE;

    private CommandParams() {
    }

    //getters
    public String getUrl() {
        return url;
    }
    public Integer getDelay() {
        return delay;
    }
    public Integer getInteractions() {
        return interactions;
    }
    public Integer getLoadWaitTime() {
        return loadWaitTime;
    }
    public String getTestCase() {
        return testCase;
    }
    public Boolean setIds() {
        return setIds;
    }
    public Boolean useS3() {
        return useS3;
    }
    public Boolean headless() {
        return headless;
    }

    //builder pattern to create CommandParams using fluent language
    public static Builder builder() {
        return new Builder();
    }

    //builder pattern to create CommandParams using fluent language
    public static class Builder {
        private CommandParams params = new CommandParams();

        public Builder url(String url) {
            params.url = url;
            return this;
        }

        public Builder delay(Integer delay) {
            params.delay = delay;
            return this;
        }

        public Builder interactions(Integer interactions) {
            params.interactions = interactions;
            return this;
        }

        public Builder loadWaitTime(Integer loadWaitTime) {
            params.loadWaitTime = loadWaitTime;
            return this;
        }

        public Builder testCase(String testCase) {
            params.testCase = testCase;
            return this;
        }
        public Builder setIds(Boolean setIds) {
            params.setIds = setIds;
            return this;
        }
        public Builder useS3(Boolean useS3) {
            params.useS3 = useS3;
            return this;
        }
        public Builder headless(Boolean headless) {
            params.headless = headless;
            return this;
        }

        public CommandParams build() {
            if(params.url == null){
                throw new IllegalArgumentException("URL is required");
            }
            return params;
        }
    }

    public static CommandParams getDefaultUseS3(final String url, final String testCase, final Boolean setIds){

        return CommandParams.builder()
            .url(url)
            .delay(300)
            .interactions(100)
            .loadWaitTime(5000)
            .useS3(Boolean.TRUE)
            .setIds(setIds)
            .testCase(testCase)
        .build();
    }    

    public static CommandParams getDefault(final String url, final String testCase, final Boolean setIds){

        return CommandParams.builder()
            .url(url)
            .useS3(Boolean.FALSE)
            .setIds(setIds)
            .testCase(testCase)
        .build();
    }

}
