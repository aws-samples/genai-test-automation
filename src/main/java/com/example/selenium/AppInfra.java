package com.example.selenium;

import java.util.Collections;
import java.util.Map;

import software.amazon.awscdk.App;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.Integration;
import software.amazon.awscdk.services.apigateway.IntegrationOptions;
import software.amazon.awscdk.services.apigateway.IntegrationResponse;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.MethodResponse;
import software.amazon.awscdk.services.apigateway.PassthroughBehavior;
import software.amazon.awscdk.services.apigateway.Resource;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.apigateway.StageOptions;
import software.amazon.awscdk.services.applicationautoscaling.AdjustmentType;
import software.amazon.awscdk.services.applicationautoscaling.BasicStepScalingPolicyProps;
import software.amazon.awscdk.services.applicationautoscaling.EnableScalingProps;
import software.amazon.awscdk.services.applicationautoscaling.ScalingInterval;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.NatInstanceProps;
import software.amazon.awscdk.services.ec2.NatInstanceProviderV2;
import software.amazon.awscdk.services.ec2.NatProvider;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.VpcLookupOptions;
import software.amazon.awscdk.services.ecs.AwsLogDriver;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerDefinitionOptions;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.LogDriver;
import software.amazon.awscdk.services.ecs.ScalableTaskCount;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.ssm.StringParameter;

public class AppInfra {


    public static interface Constants{

        public static final String APP_NAME = "test-automation";
        public static final String AWS_ACCOUNT = "587929909912";
        public static final String AWS_REGION = "us-east-1";
    }

    public static void main(String args[]) {

        App app = new App();

        String vpcId = (String)app.getNode().tryGetContext("useVpc");
        if( vpcId != null ){
            System.out.println("Using VPC: "+vpcId);
        }
        app.getNode().getContext("useVpc");
        new InfraStack(
            app, 
            Constants.APP_NAME, 
            vpcId, 
            new StackProps.Builder()
                .env(Environment.builder()
                    .account(Constants.AWS_ACCOUNT)
                    .region(Constants.AWS_REGION)
                    .build())
                .build());
        
        app.synth();
    }

    private static class InfraStack extends Stack {

        private Boolean useVpc = Boolean.FALSE;
        private String vpcId = null;

        public InfraStack(App app, String id, String vpcId, StackProps props) {
            super(app, id, props);
            if( vpcId != null && vpcId.startsWith("vpc-") ){
                useVpc = Boolean.TRUE;
                this.vpcId = vpcId;
            }
            createInfra();
        }

        private void createInfra() {

            // Create an SQS queue
            Queue messageQueue = Queue.Builder.create(this, Constants.APP_NAME+"-queue")
                    .visibilityTimeout(Duration.seconds(600))
                    .receiveMessageWaitTime(Duration.seconds(20))
                    .deadLetterQueue(DeadLetterQueue.builder()
                        .maxReceiveCount(3)
                        .queue(Queue.Builder.create(this, Constants.APP_NAME+"-deadletter-queue")
                            .visibilityTimeout(Duration.seconds(300))
                            .receiveMessageWaitTime(Duration.seconds(20))
                            .build())
                        .build())
                    .build();                    
            
            Queue replyMessageQueue = Queue.Builder.create(this, Constants.APP_NAME+"-reply-queue")
                    .visibilityTimeout(Duration.seconds(300))
                    .receiveMessageWaitTime(Duration.seconds(20))
                    .build();                

            //Add to Systems Manager QueueURL. Parameter Name Constants.APP_NAME and value the Queue url
            StringParameter ssmParamQueue = StringParameter.Builder.create(this, Constants.APP_NAME+"queue-url-param")
                    .parameterName("/"+Constants.APP_NAME+"/queue-url")
                    .stringValue(messageQueue.getQueueUrl())
                    .build();

            StringParameter ssmParamReplyQueue = StringParameter.Builder.create(this, Constants.APP_NAME+"queue-reply-url-param")
                    .parameterName("/"+Constants.APP_NAME+"/queue-reply-url")
                    .stringValue(replyMessageQueue.getQueueUrl())
                    .build();                   

            // Create an IAM role for API Gateway
            Role credentialsRole = Role.Builder.create(this, Constants.APP_NAME+"-apigw-role")
                    .assumedBy(ServicePrincipal.Builder.create("apigateway.amazonaws.com").build())
                    .build();

            // Attach an inline policy to the IAM role
            credentialsRole.addToPolicy(PolicyStatement.Builder.create()
                    .actions(java.util.List.of("sqs:SendMessage"))
                    .effect(Effect.ALLOW)
                    .resources(java.util.List.of(messageQueue.getQueueArn()))
                    .build());

            // Create an API Gateway REST API
            RestApi api = RestApi.Builder.create(this, Constants.APP_NAME+"-api")
                    .deploy(true)
                    .deployOptions(StageOptions.builder()
                            .stageName("prod")
                            .tracingEnabled(true)
                            .build())
                    .build();

            // Add a resource and method to the API Gateway
            Resource queue = api.getRoot().addResource("queue");
            Integration integration = Integration.Builder.create()
                    .type(software.amazon.awscdk.services.apigateway.IntegrationType.AWS)
                    .integrationHttpMethod("POST")
                    .options(IntegrationOptions.builder()
                            .credentialsRole(credentialsRole)
                            .passthroughBehavior(PassthroughBehavior.NEVER)
                            .requestParameters(Map.of("integration.request.header.Content-Type", "'application/x-www-form-urlencoded'"))
                            .requestTemplates(Map.of("application/json", "Action=SendMessage&MessageBody=$util.urlEncode(\"$input.body\")"))
                            .integrationResponses(Collections.singletonList(IntegrationResponse.builder()
                                    .statusCode("200")
                                    .responseTemplates(Map.of("application/json", "{\"done\": true}"))
                                    .build()))
                            .build())
                    .uri(String.format("arn:aws:apigateway:%s:sqs:path/%s/%s", Stack.of(this).getRegion(), Stack.of(this).getAccount(), messageQueue.getQueueName()))
                    .build();
                    
            queue.addMethod("POST", integration, MethodOptions.builder()
                .methodResponses(Collections.singletonList(MethodResponse.builder()
                        .statusCode("200")
                        .build()))
                .build());

            IVpc vpc = null;
            if( this.useVpc ){

                //import vpc using vpcId
                vpc = Vpc.fromLookup(this, Constants.APP_NAME+"-fargate-vpc", VpcLookupOptions.builder().vpcId(vpcId).build());
            }else{
                // Create a VPC with a NAT Gateway
                NatProvider natGatewayProvider = NatInstanceProviderV2.instanceV2
                (NatInstanceProps.builder()
                .instanceType(InstanceType.of(
                    InstanceClass.T3, 
                    InstanceSize.NANO))
                    .build());
            
                //NatProvider.instance(InstanceType.of(InstanceClass.T3, InstanceSize.NANO));
                vpc = Vpc.Builder.create(this, Constants.APP_NAME+"-fargate-vpc")
                        .natGatewayProvider(natGatewayProvider)
                        .natGateways(1)
                        .build();
            }

            // Create an ECS cluster
            Cluster cluster = Cluster.Builder.create(this, Constants.APP_NAME+"-ecs-cluster")
                    .vpc(vpc)
                    .build();

            // Create an IAM role for ECS tasks
            Role ecsTaskRole = Role.Builder.create(this, Constants.APP_NAME+"-ecstaskrole")
                    .assumedBy(ServicePrincipal.Builder.create("ecs-tasks.amazonaws.com").build())
                    .build();

            // Attach an inline policy to the ECS task role
            ecsTaskRole.addToPolicy(PolicyStatement.Builder.create()
                    .actions(java.util.List.of("sqs:*"))
                    .effect(Effect.ALLOW)
                    .resources(java.util.List.of(messageQueue.getQueueArn(), replyMessageQueue.getQueueArn()))
                    .build());

            // Attach an inline policy to the ECS task role
            ecsTaskRole.addToPolicy(PolicyStatement.Builder.create()
                    .actions(java.util.List.of("sqs:*"))
                    .effect(Effect.ALLOW)
                    .resources(java.util.List.of(replyMessageQueue.getQueueArn()))
                    .build());     
                    
            //ecs task role needs access to ssm parameter ssmParamQueue
            ecsTaskRole.addToPolicy(PolicyStatement.Builder.create()
                    .actions(java.util.List.of("ssm:GetParameters", "ssm:GetParameter"))
                    .effect(Effect.ALLOW)
                    .resources(java.util.List.of(ssmParamQueue.getParameterArn(), ssmParamReplyQueue.getParameterArn()))
                    .build());

            //ecs task role needs access to amazon bedrock
            ecsTaskRole.addToPolicy(PolicyStatement.Builder.create()
                    .actions(java.util.List.of("bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"))
                    .effect(Effect.ALLOW)
                    .resources(java.util.List.of("*"))
                    .build());

            // Create a Fargate task definition
            FargateTaskDefinition fargateTaskDefinition = FargateTaskDefinition.Builder.create(this, Constants.APP_NAME+"=fargateTaskDef")
                    .memoryLimitMiB(4096)
                    .cpu(2048)
                    .taskRole(ecsTaskRole)
                    .build();

            // Create a log driver for CloudWatch Logs
            LogDriver logging = AwsLogDriver.Builder.create()
                    .streamPrefix(Constants.APP_NAME)
                    .build();

            // Add a container to the task definition
            fargateTaskDefinition.addContainer("Container", ContainerDefinitionOptions.builder()
                .image(ContainerImage.fromAsset(".", null))
                .logging(logging)
                .build());

            // Create a Fargate service
            FargateService service = FargateService.Builder.create(this, Constants.APP_NAME+"-ecsService")
                    .cluster(cluster)
                    .taskDefinition(fargateTaskDefinition)
                    .desiredCount(0)
                    .build();

            // Configure task auto-scaling
            ScalableTaskCount scaling = service.autoScaleTaskCount(EnableScalingProps.builder()
                    .minCapacity(0)
                    .maxCapacity(10)
                    .build());

            // // Setup scaling metric and cooldown period
            scaling.scaleOnMetric("QueueMessagesVisibleScaling", BasicStepScalingPolicyProps.builder()
                    .metric(messageQueue.metricApproximateNumberOfMessagesVisible())
                    .adjustmentType(AdjustmentType.CHANGE_IN_CAPACITY)
                    .cooldown(Duration.seconds(600))
                    .scalingSteps(java.util.List.of(
                        ScalingInterval.builder().upper(0).change(-1).build(),
                        ScalingInterval.builder().lower(1).change(1).build()))
                    .build());                 
                    
            //s3 bucket to store screenshots
            Bucket bucket = Bucket.Builder.create(this, Constants.APP_NAME+"-bucket")
                    .build();
                
            //add to systems manager parameter store. Parameter Name Constants.APP_NAME and value the bucket name
            StringParameter ssmBucketParam = StringParameter.Builder.create(this, Constants.APP_NAME+"bucket-param")
                    .parameterName("/"+Constants.APP_NAME+"/bucket")
                    .stringValue(bucket.getBucketName())
                    .build();

            //add permissions to ecsTaskRole to write files to s3
            bucket.grantWrite(ecsTaskRole);

            //ecs task role needs access to ssm parameter ssmBucketParam
            ecsTaskRole.addToPolicy(PolicyStatement.Builder.create()
                .actions(java.util.List.of("ssm:GetParameters", "ssm:GetParameter"))
                .effect(Effect.ALLOW)
                .resources(java.util.List.of(ssmBucketParam.getParameterArn()))
                .build());
            
            CfnOutput.Builder.create(this, "VPC")
                    .description("Arn of the VPC ")
                    .value(vpc.getVpcArn())
                    .build();

            CfnOutput.Builder.create(this, "ECSCluster")
                    .description("Name of the ECS Cluster ")
                    .value(cluster.getClusterName())
                    .build();

            CfnOutput.Builder.create(this, "SQSQueue")
                    .description("Name of the SQS ")
                    .value(messageQueue.getQueueName())
                    .build();

            CfnOutput.Builder.create(this, "SQSReplyQueue")
                    .description("Name of the SQS ")
                    .value(replyMessageQueue.getQueueName())
                    .build();                

            CfnOutput.Builder.create(this, "ServiceURL")
                    .description("Application is acessible from this url")
                    .value(api.getUrl() + "queue")
                    .build();   
            
            CfnOutput.Builder.create(this, "Bucket")
                    .description("S3 Bucket Name to store screenshots and info")
                    .value(bucket.getBucketName())
                    .build();
            

        }
    }
    
}
