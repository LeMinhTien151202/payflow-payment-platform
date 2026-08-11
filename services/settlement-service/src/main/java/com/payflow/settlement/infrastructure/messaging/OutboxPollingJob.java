package com.payflow.settlement.infrastructure.messaging;
import com.payflow.settlement.application.PublishOutboxHandler;
import com.payflow.settlement.application.port.OutboxLeaseStore;
import io.micrometer.core.instrument.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component @ConditionalOnProperty(prefix="payflow.outbox",name="enabled",matchIfMissing=true)
final class OutboxPollingJob {
 private final PublishOutboxHandler publisher; private final OutboxLeaseStore store; private final OutboxPublisherOwner owner; private final MeterRegistry meters; private final AtomicLong age=new AtomicLong();
 OutboxPollingJob(PublishOutboxHandler p,OutboxLeaseStore s,OutboxPublisherOwner o,MeterRegistry m){publisher=p;store=s;owner=o;meters=m;Gauge.builder("payflow.outbox.pending.age.seconds",age,v->v.get()/1000d).register(m);}
 @Scheduled(fixedDelayString="${payflow.outbox.poll-interval:500ms}") void poll(){var r=publisher.publishAvailable(owner.value());increment("payflow.outbox.published",r.published());increment("payflow.outbox.publish.failed",r.publishFailed());increment("payflow.outbox.reclaimed",r.reclaimed());increment("payflow.outbox.failed.terminal",r.terminalFailed());age.set(Math.round(store.oldestPendingAgeSeconds()*1000));}
 private void increment(String n,int v){if(v>0)meters.counter(n).increment(v);}
}
