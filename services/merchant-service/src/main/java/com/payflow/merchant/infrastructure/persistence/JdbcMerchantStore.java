package com.payflow.merchant.infrastructure.persistence;

import com.payflow.merchant.application.port.MerchantStore;
import com.payflow.merchant.domain.MerchantProfile;
import com.payflow.merchant.domain.MerchantStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class JdbcMerchantStore implements MerchantStore {
    private final JdbcClient jdbc; private final ObjectMapper json;
    JdbcMerchantStore(JdbcClient jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public MerchantProfile create(UUID id,String code,String name,BigDecimal feeRate,BigDecimal limit,Instant now){
        try {
            jdbc.sql("""
              insert into merchant.merchants(id,code,name,status,default_currency,fee_rate,max_transaction_amount,created_at,updated_at,version)
              values(:id,:code,:name,'PENDING','VND',:fee,:limit,:now,:now,0)
              """)
              .params(Map.of("id",id,"code",code,"name",name,"fee",feeRate,"limit",limit,"now",now.atOffset(ZoneOffset.UTC))).update();
        } catch(DuplicateKeyException e){throw new com.payflow.merchant.application.MerchantException("MERCHANT_CODE_CONFLICT","Merchant code already exists",409);}
        return find(id).orElseThrow();
    }
    public Optional<MerchantProfile> find(UUID id){
        return jdbc.sql("select * from merchant.merchants where id=:id").param("id",id).query(this::map).optional();
    }
    public Optional<PaymentPolicy> findPaymentPolicy(UUID id){
        return jdbc.sql("""
          select id,status,default_currency,max_transaction_amount,
                 fee_policy_version,fee_rate,fee_rounding_mode
          from merchant.merchants where id=:id
          """).param("id",id).query((rs,n)->new PaymentPolicy(
            rs.getObject("id",UUID.class),rs.getString("status"),rs.getString("default_currency"),
            rs.getBigDecimal("max_transaction_amount"),rs.getString("fee_policy_version"),
            rs.getBigDecimal("fee_rate"),rs.getString("fee_rounding_mode"))).optional();
    }
    public MerchantProfile update(UUID id,String name,BigDecimal feeRate,BigDecimal limit,long version,Instant now){
        int changed=jdbc.sql("""
          update merchant.merchants set name=:name,fee_rate=:fee,max_transaction_amount=:limit,
          fee_policy_version='FEE_V'||(version+1),updated_at=:now,version=version+1
          where id=:id and version=:version
          """)
          .params(Map.of("id",id,"name",name,"fee",feeRate,"limit",limit,"now",now.atOffset(ZoneOffset.UTC),"version",version)).update();
        if(changed==0) throw new com.payflow.merchant.application.MerchantException("MERCHANT_VERSION_CONFLICT","Merchant version is stale",409);
        return find(id).orElseThrow();
    }
    public MerchantProfile updateStatus(UUID id,MerchantStatus status,long version,Instant now){
        int changed=jdbc.sql("""
          update merchant.merchants set status=:status,updated_at=:now,version=version+1
          where id=:id and version=:version
          """)
          .param("id",id).param("status",status.name()).param("now",now.atOffset(ZoneOffset.UTC))
          .param("version",version).update();
        if(changed==0) throw new com.payflow.merchant.application.MerchantException(
          "MERCHANT_VERSION_CONFLICT","Merchant version is stale",409);
        return find(id).orElseThrow();
    }
    public Member saveMember(UUID id,UUID merchantId,String userId,String role,Instant now){
        return jdbc.sql("""
          insert into merchant.members(id,merchant_id,user_id,role,status,created_at)
          values(:id,:merchant,:user,:role,'ACTIVE',:now)
          on conflict(merchant_id,user_id) do update set role=excluded.role,status='ACTIVE'
          returning id,merchant_id,user_id,role,status,created_at
          """)
          .param("id",id).param("merchant",merchantId).param("user",userId).param("role",role)
          .param("now",now.atOffset(ZoneOffset.UTC))
          .query((rs,n)->new Member(rs.getObject("id",UUID.class),rs.getObject("merchant_id",UUID.class),
            rs.getString("user_id"),rs.getString("role"),rs.getString("status"),
            rs.getObject("created_at",OffsetDateTime.class).toInstant())).single();
    }
    public boolean deactivateMember(UUID merchantId,UUID memberId){
        return jdbc.sql("""
          update merchant.members set status='INACTIVE'
          where id=:id and merchant_id=:merchant and status='ACTIVE'
          """).param("id",memberId).param("merchant",merchantId).update()==1;
    }
    public void insertApiKey(UUID id,UUID merchantId,String prefix,String hash,Instant expiresAt,Instant now){
        jdbc.sql("""
          insert into merchant.api_keys(id,merchant_id,key_prefix,key_hash,status,expires_at,created_at)
          values(:id,:merchant,:prefix,:hash,'ACTIVE',:expires,:now)
          """)
          .params(Map.of("id",id,"merchant",merchantId,"prefix",prefix,"hash",hash,
            "expires",expiresAt.atOffset(ZoneOffset.UTC),"now",now.atOffset(ZoneOffset.UTC))).update();
    }
    public boolean revokeApiKey(UUID merchantId,UUID keyId,Instant now){
        return jdbc.sql("""
          update merchant.api_keys set status='REVOKED',revoked_at=:now
          where id=:id and merchant_id=:merchant and status='ACTIVE'
          """)
          .params(Map.of("id",keyId,"merchant",merchantId,"now",now.atOffset(ZoneOffset.UTC))).update()==1;
    }
    public UUID upsertWebhook(UUID id,UUID merchantId,String url,String encrypted,Set<String> events,Instant now){
        jdbc.sql("""
          insert into merchant.webhooks(id,merchant_id,url,encrypted_secret,subscribed_events,enabled,created_at,updated_at)
          values(:id,:merchant,:url,:secret,cast(:events as jsonb),true,:now,:now)
          on conflict(merchant_id) do update set url=excluded.url,encrypted_secret=excluded.encrypted_secret,
          subscribed_events=excluded.subscribed_events,enabled=true,updated_at=excluded.updated_at
          """)
          .params(Map.of("id",id,"merchant",merchantId,"url",url,"secret",encrypted,
            "events",json.writeValueAsString(events),"now",now.atOffset(ZoneOffset.UTC))).update();
        return id;
    }
    public Optional<WebhookConfiguration> findWebhook(UUID merchantId){
        return jdbc.sql("""
          select id,merchant_id,url,encrypted_secret,subscribed_events::text,enabled
          from merchant.webhooks where merchant_id=:id and enabled=true
          """).param("id",merchantId)
          .query((rs,n)->new WebhookConfiguration(rs.getObject("id",UUID.class),rs.getObject("merchant_id",UUID.class),
            rs.getString("url"),rs.getString("encrypted_secret"),rs.getString("subscribed_events"),rs.getBoolean("enabled"))).optional();
    }
    public void appendAudit(UUID id,String actor,String action,String type,UUID resourceId,String before,String after,String correlationId,Instant now){
        jdbc.sql("""
          insert into merchant.audit_records(id,actor_id,action,resource_type,resource_id,before_status,after_status,correlation_id,created_at)
          values(:id,:actor,:action,:type,:resource,:before,:after,:correlation,:now)
          """)
          .param("id",id).param("actor",actor).param("action",action).param("type",type).param("resource",resourceId)
          .param("before",before).param("after",after).param("correlation",correlationId).param("now",now.atOffset(ZoneOffset.UTC)).update();
    }
    private MerchantProfile map(ResultSet rs,int n)throws SQLException{
        return new MerchantProfile(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),
          MerchantStatus.valueOf(rs.getString("status")),rs.getString("default_currency"),rs.getBigDecimal("fee_rate"),
          rs.getBigDecimal("max_transaction_amount"),rs.getObject("created_at",OffsetDateTime.class).toInstant(),
          rs.getObject("updated_at",OffsetDateTime.class).toInstant(),rs.getLong("version"));
    }
}
