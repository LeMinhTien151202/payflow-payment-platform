package com.payflow.settlement.application;

import com.payflow.settlement.application.outbox.*;
import com.payflow.settlement.application.port.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public final class PublishOutboxHandler {
    private static final int MAX_ERROR_LENGTH = 500;
    private final OutboxLeaseStore store; private final OutboxTransport transport;
    private final OutboxPublishPolicy policy; private final Clock clock;
    public PublishOutboxHandler(OutboxLeaseStore store, OutboxTransport transport, OutboxPublishPolicy policy, Clock clock) {
        this.store=store; this.transport=transport; this.policy=policy; this.clock=clock;
    }
    public OutboxBatchResult publishAvailable(String owner) {
        var events=store.claim(owner, policy.lease(), policy.batchSize());
        if(events.isEmpty()) return OutboxBatchResult.empty();
        int reclaimed=(int)events.stream().filter(ClaimedOutboxEvent::reclaimed).count(), published=0, failed=0, terminal=0, lost=0;
        Map<String,Instant> blocked=new HashMap<>();
        for(var event:events){
            if(blocked.containsKey(event.aggregateId())) { if(!store.markRetry(event.eventId(),owner,blocked.get(event.aggregateId()),"BlockedByEarlierAggregateEvent")) lost++; continue; }
            try { transport.publish(event); if(store.markPublished(event.eventId(),owner)) published++; else lost++; }
            catch(Exception ex){ failed++; var retryAt=clock.instant().plus(policy.backoffFor(event.attemptCount())); blocked.put(event.aggregateId(),retryAt);
                if(event.attemptCount()>=policy.maxAttempts()){ if(store.markFailed(event.eventId(),owner,safeError(ex))) terminal++; else lost++; }
                else if(!store.markRetry(event.eventId(),owner,retryAt,safeError(ex))) lost++;
            }
        }
        return new OutboxBatchResult(events.size(),reclaimed,published,failed,terminal,lost);
    }
    static String safeError(Exception failure){ String v=failure.getClass().getSimpleName()+(failure.getMessage()==null?"":": "+failure.getMessage()); return v.length()>MAX_ERROR_LENGTH?v.substring(0,MAX_ERROR_LENGTH):v; }
}
