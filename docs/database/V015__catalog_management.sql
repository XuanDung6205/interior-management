-- Additive and repeatable; keep SKU IDs referenced by carts/orders/inventory.
ALTER TABLE products ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_catalog_variant_product_status_price ON product_variants(product_id,status,price,id);
CREATE INDEX IF NOT EXISTS idx_catalog_image_product_sort ON product_images(product_id,sort_order,id);
CREATE INDEX IF NOT EXISTS idx_catalog_room_product ON product_rooms(room_id,product_id);
CREATE INDEX IF NOT EXISTS idx_catalog_collection_product ON collection_products(collection_id,product_id);
CREATE INDEX IF NOT EXISTS idx_catalog_material_variant ON variant_materials(material_id,variant_id);
