"""Ordered commerce migrations and explicit supported table inventories.

Keep historical inventories immutable: backup version 1 used the V012 table set.
This registry describes compatibility; SQL remains in the checked-in migrations.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = (
    "V001__create_furniture_shop.sql",
    "V002__orders_request_fingerprint.sql",
    "V003__shipments_idempotency.sql",
    "V004__shipment_audit_index.sql",
    "V005__orders_assembly_requested.sql",
    "V006__payments_confirmation_note.sql",
    "V007__refunds_verification_notes.sql",
    "V008__commerce_operations.sql",
    "V009__commerce_read_indexes.sql",
    "V010__sepay_payment_snapshots.sql",
    "V011__sepay_automation_review.sql",
    "V012__sepay_refund_execution.sql",
    "V013__tv5_vector_and_reindex_outbox.sql",
    "V014__commerce_vector_runtime.sql",
    "V015__catalog_management.sql",
)
LATEST_REVISION = "V015"
MIGRATION_RANGE = "V001-" + LATEST_REVISION
BASE_TABLES = frozenset("""
users roles user_roles user_addresses categories rooms materials brands products
product_rooms product_variants variant_materials product_images product_specifications
collections collection_products carts cart_items wishlist_items warehouses
inventory_balances coupons orders order_items order_addresses order_status_history
inventory_reservations inventory_movements coupon_redemptions payments payment_events
shipments shipment_items return_requests return_items refunds reviews review_images
warranty_requests tracking_events audit_logs chat_conversations chat_messages banners articles
""".split())
HISTORICAL_TABLES = BASE_TABLES | {"commerce_notification_outbox", "commerce_notifications"}
LATEST_TABLES = HISTORICAL_TABLES | {"vector_store", "commerce_vector_reindex_outbox"}
SUPPORTED_TABLES = {"V012": HISTORICAL_TABLES, "V014": LATEST_TABLES, LATEST_REVISION: LATEST_TABLES}
VECTOR_COLUMN_TYPES = {
    ("vector_store", "embedding"): "vector(768)",
    ("vector_store", "catalog_product_id"): "bigint",
    ("vector_store", "catalog_fingerprint"): "char(64)",
    ("vector_store", "embedding_model"): "varchar(80)",
    ("vector_store", "indexed_at"): "datetime(6)",
    ("commerce_vector_reindex_outbox", "requested_fingerprint"): "char(64)",
    ("commerce_vector_reindex_outbox", "lease_token"): "char(36)",
    ("commerce_vector_reindex_outbox", "lease_until"): "datetime(6)",
    ("commerce_vector_reindex_outbox", "requested_by"): "bigint",
    ("commerce_vector_reindex_outbox", "retry_reason"): "varchar(1000)",
    ("commerce_vector_reindex_outbox", "retried_by"): "bigint",
    ("commerce_vector_reindex_outbox", "retried_at"): "datetime(6)",
}


def verify_vector_columns(result):
    """Verify actual types, including dimensions, rather than trusting table names."""
    rows = [line.split("\t") for line in result.splitlines()]
    if any(len(row) != 3 for row in rows):
        raise RuntimeError("Invalid commerce V014 column inventory; expected VECTOR(768) and runtime metadata.")
    actual = {(table, column): re.sub(r"^bigint\([0-9]+\)$", "bigint", kind.lower())
              for table, column, kind in rows}
    if any(actual.get(key) != kind for key, kind in VECTOR_COLUMN_TYPES.items()):
        raise RuntimeError("Commerce V014 vector schema is incomplete or incompatible; expected VECTOR(768) and all runtime metadata/lease columns. Existing vector data was retained.")


def verify_runtime_columns(result):
    """Latest runtime also requires TV2 optimistic locking; table names alone are insufficient."""
    verify_vector_columns(result)
    columns={(table,column):re.sub(r"^bigint\([0-9]+\)$","bigint",kind.lower())
             for table,column,kind in (line.split("\t") for line in result.splitlines())}
    if columns.get(("products","version"))!="bigint":
        raise RuntimeError("Commerce V015 catalog schema is incomplete; apply V015 before running catalog management.")


def migration_paths():
    """Require the complete registry before any database mutation occurs."""
    paths = tuple(ROOT / "docs" / "database" / name for name in MIGRATIONS)
    if any(not path.is_file() for path in paths):
        raise RuntimeError("A registered commerce schema migration is missing.")
    return paths


def inventory_revision(tables):
    """Match names exactly; a count alone never establishes compatibility."""
    actual = frozenset(tables)
    for revision, expected in reversed(list(SUPPORTED_TABLES.items())):
        if actual == expected:
            return revision
    raise ValueError("Expected the exact supported 47-table or 49-table commerce inventory.")
