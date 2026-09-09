package com.payflow.settlement.infrastructure.security;
import org.springframework.http.HttpMethod;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
@Configuration(proxyBeanMethods=false) @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfig {
 @Bean SecurityFilterChain filterChain(HttpSecurity http)throws Exception{return http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable())
  .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).authorizeHttpRequests(a->a
   .requestMatchers("/actuator/health","/actuator/health/**").permitAll().requestMatchers("/swagger-ui.html","/swagger-ui/**","/v3/api-docs/**").permitAll()
   .requestMatchers(HttpMethod.POST,"/api/v1/operations/settlements/**").hasAuthority("SCOPE_settlement:run")
   .requestMatchers(HttpMethod.POST,"/api/v1/operations/reconciliation/**").hasAuthority("SCOPE_reconciliation:run")
   .requestMatchers(HttpMethod.GET,"/api/v1/operations/reconciliation/**").hasAuthority("SCOPE_reconciliation:read")
   .requestMatchers("/api/v1/settlements/**").hasAuthority("SCOPE_settlement:read").anyRequest().denyAll())
  .oauth2ResourceServer(o->o.jwt(Customizer.withDefaults())).build();}
}
