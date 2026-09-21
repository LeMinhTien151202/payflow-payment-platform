package com.payflow.merchant.infrastructure.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods=false)
@EnableMethodSecurity
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfig {
 @Bean SecurityFilterChain filterChain(HttpSecurity http)throws Exception{
  return http.csrf(c->c.disable()).httpBasic(c->c.disable()).formLogin(c->c.disable())
   .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
   .authorizeHttpRequests(a->a
    .requestMatchers("/actuator/health","/actuator/health/**").permitAll()
    .requestMatchers("/swagger-ui.html","/swagger-ui/**","/v3/api-docs/**").permitAll()
    .requestMatchers("/internal/**").hasAuthority("SCOPE_merchant:internal:read")
    .requestMatchers(HttpMethod.GET,"/api/v1/merchants/**").hasAnyAuthority(
      "SCOPE_merchant:read","SCOPE_merchant:write","SCOPE_merchant:read:any","SCOPE_merchant:write:any")
    .requestMatchers(HttpMethod.POST,"/api/v1/merchants/**").hasAnyAuthority(
      "SCOPE_merchant:write","SCOPE_merchant:write:any")
    .requestMatchers(HttpMethod.PUT,"/api/v1/merchants/**").hasAnyAuthority(
      "SCOPE_merchant:write","SCOPE_merchant:write:any")
    .requestMatchers(HttpMethod.DELETE,"/api/v1/merchants/**").hasAnyAuthority(
      "SCOPE_merchant:write","SCOPE_merchant:write:any")
    .anyRequest().denyAll())
   .oauth2ResourceServer(o->o.jwt(jwt->jwt.jwtAuthenticationConverter(
     com.payflow.security.jwt.PayFlowJwtAuthenticationConverters.servlet()))).build();
 }
}
