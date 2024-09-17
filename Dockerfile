FROM --platform=linux/amd64 alpine:3.19

ARG version=22.0.1.8.1

# Please note that the THIRD-PARTY-LICENSE could be out of date if the base image has been updated recently.
# The Corretto team will update this file but you may see a few days' delay.
RUN wget -O /THIRD-PARTY-LICENSES-20200824.tar.gz https://corretto.aws/downloads/resources/licenses/alpine/THIRD-PARTY-LICENSES-20200824.tar.gz && \
    echo "82f3e50e71b2aee21321b2b33de372feed5befad6ef2196ddec92311bc09becb  /THIRD-PARTY-LICENSES-20200824.tar.gz" | sha256sum -c - && \
    tar x -ovzf THIRD-PARTY-LICENSES-20200824.tar.gz && \
    rm -rf THIRD-PARTY-LICENSES-20200824.tar.gz && \
    wget -O /etc/apk/keys/amazoncorretto.rsa.pub https://apk.corretto.aws/amazoncorretto.rsa.pub && \
    SHA_SUM="6cfdf08be09f32ca298e2d5bd4a359ee2b275765c09b56d514624bf831eafb91" && \
    echo "${SHA_SUM}  /etc/apk/keys/amazoncorretto.rsa.pub" | sha256sum -c - && \
    echo "https://apk.corretto.aws" >> /etc/apk/repositories && \
    apk add --no-cache amazon-corretto-22=$version-r0 && \
    rm -rf /usr/lib/jvm/java-22-amazon-corretto/lib/src.zip

ENV LANG C.UTF-8

ENV JAVA_HOME=/usr/lib/jvm/default-jvm
ENV PATH=$PATH:/usr/lib/jvm/default-jvm/bin
RUN mkdir -p /u01/deploy
RUN mkdir -p /u01/deploy/state
RUN mkdir -p /u01/deploy/output
WORKDIR /u01/deploy

RUN apk update

# add jar
COPY target/genai-selenium-1.0-SNAPSHOT.jar genai-selenium.jar

# Installs latest Chromium package.
RUN apk upgrade --no-cache --available \
    && apk add --no-cache \
      chromium-swiftshader \
      ttf-freefont \
      font-noto-emoji \
    && apk add --no-cache \
      --repository=https://dl-cdn.alpinelinux.org/alpine/edge/community \
      font-wqy-zenhei

RUN apk add --no-cache chromium-chromedriver

RUN which chromedriver

RUN adduser -D awsuser
RUN chown -R awsuser /u01

# Configure user
USER awsuser

#health check if java process is running
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 CMD ps -ef | grep 'genai-selenium' || exit 1
#ENTRYPOINT [ "sh", "-c", "java -Xms512m -Xmx850m -Dwebdriver.chrome.whitelistedIps= -Dio.netty.noUnsafe -jar /u01/deploy/awsdoc-crawler.jar"]
#To get rid of the error: listen on IPv6 failed with error ERR_ADDRESS_INVALID
ENTRYPOINT [ "sh", "-c", "java -Xmx3g -Dtest-automation.use.sqs=true -Dwebdriver.chrome.whitelistedIps= -jar /u01/deploy/genai-selenium.jar"]
EXPOSE 9090