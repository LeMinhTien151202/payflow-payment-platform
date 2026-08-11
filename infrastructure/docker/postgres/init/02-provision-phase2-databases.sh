#!/bin/bash
#
# Idempotent upgrade helper for a PostgreSQL volume that was created before Phase 2.
# The normal entrypoint executes this automatically only for a brand-new volume. For an existing
# local volume run it explicitly as documented in docs/runbooks/phase2-local.md.

set -euo pipefail

ensure_service_database() {
    local database="$1"
    local role="$2"
    local password="$3"

    if psql --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
        --tuples-only --no-align --command "SELECT 1 FROM pg_roles WHERE rolname='${role}'" \
        | grep -qx 1; then
        psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
            --command "ALTER ROLE \"${role}\" WITH LOGIN PASSWORD '${password}'"
    else
        psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
            --command "CREATE ROLE \"${role}\" WITH LOGIN PASSWORD '${password}'"
    fi

    if ! psql --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
        --tuples-only --no-align --command "SELECT 1 FROM pg_database WHERE datname='${database}'" \
        | grep -qx 1; then
        psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
            --command "CREATE DATABASE \"${database}\" OWNER \"${role}\""
    fi

    psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" <<-EOSQL
        ALTER DATABASE "${database}" OWNER TO "${role}";
        REVOKE ALL ON DATABASE "${database}" FROM PUBLIC;
        GRANT CONNECT, TEMPORARY ON DATABASE "${database}" TO "${role}";
EOSQL
}

ensure_service_database \
    "payflow_account" \
    "${PAYFLOW_ACCOUNT_DB_USERNAME}" \
    "${PAYFLOW_ACCOUNT_DB_PASSWORD}"

ensure_service_database \
    "payflow_ledger" \
    "${PAYFLOW_LEDGER_DB_USERNAME}" \
    "${PAYFLOW_LEDGER_DB_PASSWORD}"

ensure_service_database \
    "payflow_merchant" \
    "${PAYFLOW_MERCHANT_DB_USERNAME}" \
    "${PAYFLOW_MERCHANT_DB_PASSWORD}"

ensure_service_database \
    "payflow_reporting" \
    "${PAYFLOW_REPORTING_DB_USERNAME}" \
    "${PAYFLOW_REPORTING_DB_PASSWORD}"

ensure_service_database \
    "payflow_settlement" \
    "${PAYFLOW_SETTLEMENT_DB_USERNAME}" \
    "${PAYFLOW_SETTLEMENT_DB_PASSWORD}"

echo "Phase 2 database provisioning complete"
