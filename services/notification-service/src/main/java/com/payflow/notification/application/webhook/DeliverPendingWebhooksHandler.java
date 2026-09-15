package com.payflow.notification.application.webhook;

import com.payflow.notification.application.port.WebhookDeliveryStore;
import java.time.Clock;
import java.time.Duration;
import org.springframework.stereotype.Service;

@Service
public class DeliverPendingWebhooksHandler {
 private final WebhookDeliveryStore store; private final WebhookTransport transport; private final WebhookRetryPolicy policy; private final Clock clock;
 public DeliverPendingWebhooksHandler(WebhookDeliveryStore store,WebhookTransport transport,WebhookRetryPolicy policy,Clock clock){
  this.store=store;this.transport=transport;this.policy=policy;this.clock=clock;
 }
 public int deliver(String owner,Duration lease,int limit){
  var claimed=store.claim(owner,lease,limit);
  for(var item:claimed){
   try{
    var result=transport.send(item.merchantId(),item.eventId(),item.eventType(),item.rawBody());
    if(result.status()!=null&&result.status()>=200&&result.status()<300){
     store.delivered(item.id(),owner,result.status(),clock.instant()); continue;
    }
    var decision=policy.classify(item.attemptCount(),result.status(),result.networkFailure());
    if(decision.retry())store.retry(item.id(),owner,result.status()==null?0:result.status(),result.safeExcerpt(),clock.instant().plus(decision.delay()));
    else store.dead(item.id(),owner,result.status(),result.safeExcerpt(),"WEBHOOK_PERMANENT_FAILURE");
   }catch(RuntimeException failure){
    var decision=policy.classify(item.attemptCount(),null,true);
    if(decision.retry())store.retry(item.id(),owner,0,"network failure",clock.instant().plus(decision.delay()));
    else store.dead(item.id(),owner,null,"network failure","WEBHOOK_RETRY_EXHAUSTED");
   }
  }
  return claimed.size();
 }
}
