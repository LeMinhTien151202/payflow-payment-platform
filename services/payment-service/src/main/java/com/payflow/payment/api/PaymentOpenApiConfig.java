package com.payflow.payment.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/** Runtime OpenAPI metadata for the public API owned by payment-service. */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(
                title = "PayFlow Payment Service API",
                version = "1.0.0",
                description =
                        "API nhận payment/refund và tra cứu trạng thái. Hai lệnh ghi trả HTTP 202 "
                                + "sau khi dữ liệu cùng outbox event đã được lưu bền vững; xử lý "
                                + "risk, account và ledger tiếp tục bất đồng bộ qua Kafka. Vì vậy "
                                + "202 là đã nhận xử lý, không phải thanh toán đã thành công.",
                contact = @Contact(name = "PayFlow portfolio project"),
                license = @License(name = "Portfolio / sandbox use")),
        servers = {
            @Server(
                    url = "/",
                    description =
                            "Cùng origin với Swagger UI (payment-service trực tiếp; chỉ dùng local/debug)")
        },
        security = @SecurityRequirement(name = PaymentOpenApiConfig.BEARER_AUTH))
@SecurityScheme(
        name = PaymentOpenApiConfig.BEARER_AUTH,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description =
                "JWT do realm payflow của Keycloak cấp. Token phải có merchant_id và scope "
                        + "payment:read hoặc payment:write theo API được gọi.")
public class PaymentOpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
}
