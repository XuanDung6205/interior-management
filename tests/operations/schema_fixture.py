"""Hand-checked schema inventory for recovery contract tests."""
HISTORICAL_TABLES = frozenset("""
users roles user_roles user_addresses categories rooms materials brands products
product_rooms product_variants variant_materials product_images product_specifications
collections collection_products carts cart_items wishlist_items warehouses
inventory_balances coupons orders order_items order_addresses order_status_history
inventory_reservations inventory_movements coupon_redemptions payments payment_events
shipments shipment_items return_requests return_items refunds reviews review_images
warranty_requests tracking_events audit_logs chat_conversations chat_messages banners
articles commerce_notification_outbox commerce_notifications
""".split())
LATEST_TABLES = HISTORICAL_TABLES | {"vector_store", "commerce_vector_reindex_outbox"}
LATEST_COLUMNS = """products\tversion\tbigint(20)
vector_store\tembedding\tvector(768)
vector_store\tcatalog_product_id\tbigint(20)
vector_store\tcatalog_fingerprint\tchar(64)
vector_store\tembedding_model\tvarchar(80)
vector_store\tindexed_at\tdatetime(6)
commerce_vector_reindex_outbox\trequested_fingerprint\tchar(64)
commerce_vector_reindex_outbox\tlease_token\tchar(36)
commerce_vector_reindex_outbox\tlease_until\tdatetime(6)
commerce_vector_reindex_outbox\trequested_by\tbigint(20)
commerce_vector_reindex_outbox\tretry_reason\tvarchar(1000)
commerce_vector_reindex_outbox\tretried_by\tbigint(20)
commerce_vector_reindex_outbox\tretried_at\tdatetime(6)"""


def fixture_sql(latest=False):
    tables = HISTORICAL_TABLES if not latest else LATEST_TABLES - {"vector_store"}
    sql = "\n".join(
        f"CREATE TABLE `{name}` (`id` int NOT NULL, `value` varchar(100), PRIMARY KEY (`id`)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n"
        f"INSERT INTO `{name}` (`id`, `value`) VALUES (1,'hello; -- not SQL \\' quote');"
        for name in sorted(tables)
    )
    if latest:
        sql += "\n" + (
            "CREATE TABLE `vector_store` (`id` varchar(36) NOT NULL, `content` longtext NOT NULL, "
            "`metadata` longtext DEFAULT NULL CHECK (json_valid(`metadata`)), `embedding` vector(768) NOT NULL, "
            "PRIMARY KEY (`id`), VECTOR KEY `idx_vector_store_embedding` (`embedding`) `M`=6 `DISTANCE`=cosine) "
            "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;\n"
            "INSERT INTO `vector_store` (`id`, `content`, `metadata`, `embedding`) "
            "VALUES ('fixture-vector','public chair','{\"productId\":1}',0x0000803F" + "00000000" * 767 + ");"
        )
    return sql
