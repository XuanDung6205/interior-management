"""One distinct licensed illustrative photo for each NN-DEMO product SKU.

Each row is (provider, asset identifier, source page). The order follows
FAMILIES x STYLES in seed_demo_catalog.py. Sources are documented per SKU.
"""

PHOTOS = (
    # Sofa vải
    (("project", "sofa-modena.jpg", ""),
     ("stocksnap", "WDVIQZEXML", ""),
     ("unsplash", "photo-1754557409108-cccb4205d5dc", "https://unsplash.com/photos/a-cozy-living-room-with-a-comfortable-sofa-PT13eKtZJSg"),
     ("unsplash", "photo-1776186243368-50a3ed7cd200", "https://unsplash.com/photos/a-textured-beige-sofa-with-a-pillow-and-curtains-KbjrgKlkcBI"),
     ("stocksnap", "OZ2BTL055Z", "")),
    # Sofa thư giãn
    (("project", "gallery-soft-living.jpg", ""),
     ("project", "gallery-living-room.jpg", ""),
     ("project", "home-hero.jpg", ""),
     ("unsplash", "photo-1775029676739-86443af4010d", "https://unsplash.com/photos/a-beige-sofa-with-decorative-pillows-and-a-side-table-pYqXT6hoRbQ"),
     ("unsplash", "photo-1771577067936-680c2f795e5f", "https://unsplash.com/photos/two-beige-sofas-in-a-modern-living-room-oGJLFNnD_Ek")),
    # Bàn trà
    (("project", "ban-tra-oslo.jpg", ""),
     ("stocksnap", "8JQITZ05LX", ""),
     ("stocksnap", "L4SNGYZWL4", ""),
     ("stocksnap", "Y1OCF92P69", ""),
     ("unsplash", "photo-1789799223021-e8fb04e10b0b", "https://unsplash.com/photos/wooden-coffee-table-in-living-room-jYUjS2uM_I0")),
    # Ghế bành
    (("project", "ghe-aalto.jpg", ""),
     ("project", "gallery-reading-corner.jpg", ""),
     ("stocksnap", "SHE40GOPPL", ""),
     ("unsplash", "photo-1549436304-3bfb7b6aab87", "https://unsplash.com/photos/beige-armchair-KM4DlEItBFk"),
     ("unsplash", "photo-1789799223024-777e6d9f3423", "https://unsplash.com/photos/armchairs-and-wooden-table-in-living-room-DEt9ZUZ6E1I")),
    # Bàn ăn
    (("project", "gallery-dining-room.jpg", ""),
     ("stocksnap", "11VBKSE76A", ""),
     ("stocksnap", "LTCQNB2L7A", ""),
     ("stocksnap", "NCEEGGU7W4", ""),
     ("stocksnap", "3J13XAT5LM", "")),
    # Ghế ăn
    (("stocksnap", "VMJ6JJFFWR", ""),
     ("stocksnap", "LHKILYV8X0", ""),
     ("stocksnap", "EWWJV5R5RR", ""),
     ("stocksnap", "CCF37A44AB", ""),
     ("unsplash", "photo-1706275513127-e06fa0f0bb85", "https://unsplash.com/photos/a-row-of-wooden-chairs-sitting-next-to-a-wooden-table-IiU7i-4vvbM")),
    # Giường ngủ
    (("project", "gallery-bedroom.jpg", ""),
     ("stocksnap", "C26E1DKA02", ""),
     ("stocksnap", "EGXWIV409M", ""),
     ("stocksnap", "12B5400E80", ""),
     ("stocksnap", "XWI13131WH", "")),
    # Tủ lưu trữ
    (("project", "gallery-entryway.jpg", ""),
     ("stocksnap", "LR60D994WA", ""),
     ("stocksnap", "YISUDLOYC3", ""),
     ("stocksnap", "HGD72DTYOO", ""),
     ("unsplash", "photo-1755288556494-df59e71850b8", "https://unsplash.com/photos/modern-wooden-cabinets-with-an-open-door-and-shelves-o98pufp8Khk")),
    # Kệ sách
    (("stocksnap", "14B430281B", ""),
     ("stocksnap", "0SP70JWWOK", ""),
     ("stocksnap", "1XAJHYVE9L", ""),
     ("stocksnap", "KVIJTZ5LHP", ""),
     ("unsplash", "photo-1767020990986-e6c41985d8a9", "https://unsplash.com/photos/books-and-decor-on-wooden-shelves-with-warm-lighting-pHcUSqqCYh8")),
    # Bàn console
    (("unsplash", "photo-1767963758785-b9def36c13e3", "https://unsplash.com/photos/modern-wooden-sideboard-with-decorative-items-and-lamp-UrQeWGobTnI"),
     ("unsplash", "photo-1585128792020-803d29415281", "https://unsplash.com/photos/minimalist-living-room-with-wooden-sideboard-aX1TTOuq83M"),
     ("unsplash", "photo-1556408977-c2ca2733ab41", "https://unsplash.com/photos/white-wooden-sideboard-CZee_4W6hRI"),
     ("unsplash", "photo-1778731525521-d0e6477b1bda", "https://unsplash.com/photos/modern-entryway-with-console-table-and-mirror-O-8VMFGgG_A"),
     ("unsplash", "photo-1758974782657-e5ada4b01c3c", "https://unsplash.com/photos/modern-wooden-sideboard-with-shelves-and-decor-OufPLukHBoE")),
)

assert len(PHOTOS) == 10 and all(len(group) == 5 for group in PHOTOS)
assert len({(provider, identifier) for group in PHOTOS for provider, identifier, _ in group}) == 50


def filename(family_index, style_index):
    return f"NN-DEMO-{family_index:02d}-{style_index:02d}.jpg"


def source_url(photo):
    provider, identifier, _ = photo
    if provider == "stocksnap":
        return f"https://cdn.stocksnap.io/img-thumbs/960w/{identifier}.jpg"
    if provider == "unsplash":
        return f"https://images.unsplash.com/{identifier}?auto=format&fit=crop&w=960&q=80&fm=jpg"
    return None
