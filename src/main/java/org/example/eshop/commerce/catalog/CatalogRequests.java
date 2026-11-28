package org.example.eshop.commerce.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public final class CatalogRequests {
    private CatalogRequests() {}
    public record Taxonomy(@NotBlank @Size(max=120) String name,
        @NotBlank @Pattern(regexp="[a-z0-9]+(?:-[a-z0-9]+)*") @Size(max=150) String slug,
        @Size(max=10000) String description, Long parentId, @Size(max=1000) String imageUrl,
        @Size(max=1000) String logoUrl, @Size(max=10000) String careInstructions,
        @Pattern(regexp="[A-Z]{2}") String countryCode, @Min(0) Integer sortOrder, boolean active) {
        public Taxonomy { if(sortOrder==null) sortOrder=0; }
    }
    public record Product(Long version, @NotBlank @Size(max=200) String name,
        @NotBlank @Size(max=220) @Pattern(regexp="[a-z0-9]+(?:-[a-z0-9]+)*") String slug,
        @NotNull @Positive Long categoryId, @Positive Long brandId,
        @NotBlank @Pattern(regexp="DRAFT|ACTIVE|DISCONTINUED|ARCHIVED") String status,
        @Size(max=500) String shortDescription, @Size(max=30000) String description,
        @Size(max=10000) String careInstructions, @Min(0) @Max(1200) int warrantyMonths,
        boolean assemblyRequired, @Size(max=200) String seoTitle, @Size(max=320) String seoDescription,
        @NotNull @Size(max=100) List<@NotNull @Positive Long> roomIds,
        @NotNull @Size(max=100) List<@NotNull @Positive Long> collectionIds,
        @NotEmpty @Size(max=50) List<@NotNull @Valid Variant> variants,
        @NotNull @Size(max=30) List<@NotNull @Valid Image> images,
        @NotNull @Size(max=80) List<@NotNull @Valid Specification> specifications) {}
    public record Variant(@Positive Long id, @NotBlank @Size(max=80) String sku,
        @NotBlank @Size(max=180) String variantKey, @NotBlank @Size(max=200) String name,
        @NotBlank @Pattern(regexp="ACTIVE|INACTIVE|DISCONTINUED") String status,
        @Size(max=80) String colorName, @Pattern(regexp="#[0-9a-fA-F]{6}") String colorHex,
        @Size(max=100) String sizeLabel,
        @DecimalMin(value="0",inclusive=false) @Digits(integer=8,fraction=2) BigDecimal widthMm,
        @DecimalMin(value="0",inclusive=false) @Digits(integer=8,fraction=2) BigDecimal depthMm,
        @DecimalMin(value="0",inclusive=false) @Digits(integer=8,fraction=2) BigDecimal heightMm,
        @DecimalMin(value="0",inclusive=false) @Digits(integer=7,fraction=3) BigDecimal weightKg,
        @NotNull @DecimalMin("0") @Digits(integer=13,fraction=2) BigDecimal price,
        @DecimalMin("0") @Digits(integer=13,fraction=2) BigDecimal compareAtPrice,
        @Min(0) @Max(3650) int leadTimeDays,
        @NotNull @Size(max=30) List<@NotNull @Positive Long> materialIds) {}
    public record Image(@NotBlank @Size(max=1000) String url, @Size(max=255) String altText,
        @Positive Long variantId, @Min(0) int sortOrder) {}
    public record Specification(@NotBlank @Size(max=100) String name,
        @NotBlank @Size(max=1000) String value, @Size(max=30) String unit, @Min(0) int sortOrder) {}
}
