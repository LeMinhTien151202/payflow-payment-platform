package com.payflow.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ProblemDetailsTest {

    @Test
    @DisplayName("builds a body carrying status, detail, code and correlation id")
    void buildsFullBody() {
        ProblemDetail problem =
                ProblemDetails.of(
                        HttpStatus.FORBIDDEN,
                        PayFlowErrorCode.AUTH_FORBIDDEN,
                        "Access to this resource is not permitted.",
                        "req-123");

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(problem.getDetail()).isEqualTo("Access to this resource is not permitted.");
        assertThat(problem.getProperties())
                .containsEntry(ProblemDetails.FIELD_CODE, "AUTH_FORBIDDEN")
                .containsEntry(ProblemDetails.FIELD_CORRELATION_ID, "req-123");
    }

    /**
     * The correlation id reaches the response body straight from a client-controlled header. An
     * unsafe value must be dropped rather than reflected, otherwise the error response becomes a
     * reflection point for injected content.
     */
    @Test
    @DisplayName("an unsafe correlation id is omitted rather than reflected to the client")
    void omitsUnsafeCorrelationId() {
        ProblemDetail problem =
                ProblemDetails.of(
                        HttpStatus.BAD_REQUEST,
                        PayFlowErrorCode.REQUEST_VALIDATION_FAILED,
                        "Request is not valid.",
                        "<script>alert(1)</script>");

        assertThat(problem.getProperties())
                .containsKey(ProblemDetails.FIELD_CODE)
                .doesNotContainKey(ProblemDetails.FIELD_CORRELATION_ID);
    }

    @Test
    @DisplayName("a missing correlation id leaves the field out but still sets the code")
    void omitsAbsentCorrelationId() {
        ProblemDetail problem =
                ProblemDetails.of(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        PayFlowErrorCode.INTERNAL_ERROR,
                        "The request could not be processed.",
                        null);

        assertThat(problem.getProperties())
                .containsEntry(ProblemDetails.FIELD_CODE, "INTERNAL_ERROR")
                .doesNotContainKey(ProblemDetails.FIELD_CORRELATION_ID);
    }

    @Test
    @DisplayName("enrich adds the PayFlow members to a body Spring already built")
    void enrichesSpringBuiltBody() {
        ProblemDetail springBuilt =
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No static resource.");

        ProblemDetails.enrich(springBuilt, PayFlowErrorCode.RESOURCE_NOT_FOUND, "req-456");

        assertThat(springBuilt.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(springBuilt.getProperties())
                .containsEntry(ProblemDetails.FIELD_CODE, "RESOURCE_NOT_FOUND")
                .containsEntry(ProblemDetails.FIELD_CORRELATION_ID, "req-456");
    }

    /**
     * Clients branch on {@code code}, so a rename is a breaking API change. Asserting the literal
     * strings makes that break visible in this build instead of in a consumer's.
     */
    @Test
    @DisplayName("error codes are stable wire values")
    void errorCodesAreStable() {
        assertThat(PayFlowErrorCode.AUTH_UNAUTHENTICATED.code()).isEqualTo("AUTH_UNAUTHENTICATED");
        assertThat(PayFlowErrorCode.AUTH_FORBIDDEN.code()).isEqualTo("AUTH_FORBIDDEN");
        assertThat(PayFlowErrorCode.REQUEST_VALIDATION_FAILED.code())
                .isEqualTo("REQUEST_VALIDATION_FAILED");
        assertThat(PayFlowErrorCode.RESOURCE_NOT_FOUND.code()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(PayFlowErrorCode.INTERNAL_ERROR.code()).isEqualTo("INTERNAL_ERROR");
    }
}
