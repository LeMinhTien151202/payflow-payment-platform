package com.payflow.notification.application.webhook;

import java.time.Duration;

public final class WebhookRetryPolicy {
    private static final Duration[] DELAYS={Duration.ZERO,Duration.ofSeconds(30),Duration.ofMinutes(2),Duration.ofMinutes(10),Duration.ofHours(1)};
    public Decision classify(int attempt,Integer status,boolean networkFailure){
        boolean transientFailure=networkFailure||status==null||status==408||status==429||status>=500;
        if(!transientFailure)return new Decision(false,true,Duration.ZERO);
        if(attempt>=DELAYS.length)return new Decision(false,true,Duration.ZERO);
        return new Decision(true,false,DELAYS[attempt]);
    }
    public record Decision(boolean retry,boolean terminal,Duration delay){}
}
