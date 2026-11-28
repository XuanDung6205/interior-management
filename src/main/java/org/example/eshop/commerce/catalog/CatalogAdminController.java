package org.example.eshop.commerce.catalog;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/catalog")
public class CatalogAdminController {
    private final CatalogAdminService catalog;
    public CatalogAdminController(CatalogAdminService catalog) { this.catalog=catalog; }
    @GetMapping("/options") public Object options() { return catalog.options(); }
    @GetMapping("/products") public Object products(@RequestParam(defaultValue="") String query,
        @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="12") int size) { return catalog.products(query,page,size); }
    @GetMapping("/products/{id}") public Object product(@PathVariable long id) { return catalog.product(id); }
    @PostMapping("/products") @ResponseStatus(HttpStatus.CREATED)
    public Object create(@Valid @RequestBody CatalogRequests.Product body) { return catalog.save(null,body); }
    @PutMapping("/products/{id}") public Object update(@PathVariable long id,@Valid @RequestBody CatalogRequests.Product body) { return catalog.save(id,body); }
    @PostMapping("/taxonomies/{kind}") @ResponseStatus(HttpStatus.CREATED)
    public Object createTaxonomy(@PathVariable String kind,@Valid @RequestBody CatalogRequests.Taxonomy body) { return catalog.saveTaxonomy(kind,null,body); }
    @PutMapping("/taxonomies/{kind}/{id}")
    public Object updateTaxonomy(@PathVariable String kind,@PathVariable long id,@Valid @RequestBody CatalogRequests.Taxonomy body) { return catalog.saveTaxonomy(kind,id,body); }
}
