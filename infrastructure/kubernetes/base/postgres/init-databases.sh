#!/bin/sh
set -eu

create_database() {
  database_name="$1"
  database_user="$2"
  database_password="$3"

  psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set=db_name="$database_name" --set=db_user="$database_user" \
    --set=db_password="$database_password" <<-'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'db_user', :'db_password')
WHERE NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = :'db_user') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'db_name', :'db_user')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'db_name') \gexec
SQL
}

create_database auth_db auth_user "$AUTH_DB_PASSWORD"
create_database user_db user_user "$USER_DB_PASSWORD"
create_database product_db product_user "$PRODUCT_DB_PASSWORD"
create_database inventory_db inventory_user "$INVENTORY_DB_PASSWORD"
create_database cart_db cart_user "$CART_DB_PASSWORD"
create_database order_db order_user "$ORDER_DB_PASSWORD"
create_database payment_db payment_user "$PAYMENT_DB_PASSWORD"
create_database notification_db notification_user "$NOTIFICATION_DB_PASSWORD"
create_database search_db search_user "$SEARCH_DB_PASSWORD"
create_database recommendation_db recommendation_user "$RECOMMENDATION_DB_PASSWORD"

