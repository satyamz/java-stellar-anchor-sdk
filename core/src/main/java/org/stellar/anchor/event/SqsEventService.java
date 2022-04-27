package org.stellar.anchor.event;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.sqs.AmazonSQSAsync;
import com.amazonaws.services.sqs.AmazonSQSAsyncClientBuilder;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.google.gson.Gson;
import java.util.Map;
import org.stellar.anchor.config.SqsConfig;
import org.stellar.anchor.event.models.AnchorEvent;
import org.stellar.anchor.util.Log;

public class SqsEventService implements EventPublishService {
  final AmazonSQSAsync sqsClient;
  final Map<String, String> eventTypeToQueue;
  final boolean useSingleQueue;

  public SqsEventService(SqsConfig sqsConfig) {
    this.sqsClient =
        AmazonSQSAsyncClientBuilder.standard()
            .withRegion(sqsConfig.getRegion())
            .withCredentials(
                new AWSStaticCredentialsProvider(
                    new BasicAWSCredentials(sqsConfig.getAccessKey(), sqsConfig.getSecretKey())))
            .build();

    this.eventTypeToQueue = sqsConfig.getEventTypeToQueue();
    this.useSingleQueue = sqsConfig.isUseSingleQueue();
  }

  public void publish(AnchorEvent event) {
    try {
      // TODO implement batching
      // TODO retry logic?
      String queue;
      if (useSingleQueue) {
        queue = eventTypeToQueue.get("all");
      } else {
        queue = eventTypeToQueue.get(event.getType());
      }
      Gson gson = new Gson();
      String eventStr = gson.toJson(event);

      String queueUrl = sqsClient.getQueueUrl(queue).getQueueUrl();
      SendMessageRequest sendMessageRequest =
          new SendMessageRequest()
              .withQueueUrl(queueUrl)
              .withMessageBody(eventStr)
              .withMessageGroupId(queue)
              .withMessageDeduplicationId(event.getEventId());

      sendMessageRequest.addMessageAttributesEntry(
          "anchor-event-class",
          new MessageAttributeValue()
              .withDataType("String")
              .withStringValue(event.getClass().getSimpleName()));
      sqsClient.sendMessage(sendMessageRequest);

    } catch (Exception ex) {
      Log.errorEx(ex);
    }
  }
}