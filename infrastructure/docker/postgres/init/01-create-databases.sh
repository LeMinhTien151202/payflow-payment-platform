#!/bin/bash
#
# Provisions one database per service, each owned by its own role.
#
# ARCHITECTURE.md mandates database-per-service: a service may not read another service's tables,
# and separate roles are what make that violation fail at the connection rather than at code review.
#
# The PostgreSQL entrypoint runs files in this directory exactly once, when the data volume is
# empty. To re-run it:  docker compose down -v && docker compose up -d
#
# This is a .sh and not a .sql file because plain .sql files receive no environment substitution,
# and the passwords must come from the environment rather than from a committed file.

set -euo pipefail

# CREATE ROLE cannot take a bind parameter for PASSWORD, so the value is interpolated. Acceptable
# only because this runs against a disposable local container with values from .env; never reuse
# this pattern where the input is not fully controlled.
create_service_database() {
    local database="$1"
    local role="$2"
    local password="$3"

    echo "provisioning database ${database} owned by ${role}"

    psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" <<-EOSQL
        CREATE ROLE "${role}" WITH LOGIN PASSWORD '${password}';
        CREATE DATABASE "${database}" OWNER "${role}";

        -- PUBLIC can connect to any database by default. Revoking it keeps a future service's
        -- role from reaching this database just because it exists on the same server.
        REVOKE ALL ON DATABASE "${database}" FROM PUBLIC;
        GRANT CONNECT, TEMPORARY ON DATABASE "${database}" TO "${role}";
EOSQL
}

# payment-service. Flyway creates and owns the "payment" schema inside this database.
create_service_database \
    "payflow_payment" \
    "${PAYFLOW_PAYMENT_DB_USERNAME}" \
    "${PAYFLOW_PAYMENT_DB_PASSWORD}"

# account-ledger-service. The MVP keeps Account and Ledger as separate schemas inside this one
# deployable, but no other service receives this role.
create_service_database \
    "payflow_account_ledger" \
    "${PAYFLOW_ACCOUNT_LEDGER_DB_USERNAME}" \
    "${PAYFLOW_ACCOUNT_LEDGER_DB_PASSWORD}"

# risk-service. Redis remains an ephemeral signal cache; PostgreSQL owns durable assessments/inbox.
create_service_database \
    "payflow_risk" \
    "${PAYFLOW_RISK_DB_USERNAME}" \
    "${PAYFLOW_RISK_DB_PASSWORD}"

# notification-service owns delivery state and its durable consumer inbox.
create_service_database \
    "payflow_notification" \
    "${PAYFLOW_NOTIFICATION_DB_USERNAME}" \
    "${PAYFLOW_NOTIFICATION_DB_PASSWORD}"

# Keycloak. Not a PayFlow service, but it needs durable storage so local realm changes survive a
# restart.
create_service_database \
    "payflow_keycloak" \
    "${KEYCLOAK_DB_USERNAME}" \
    "${KEYCLOAK_DB_PASSWORD}"

# Services added in later phases each get their own entry here. Do not let a new service share an
# existing database.

echo "database provisioning complete"
