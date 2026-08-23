package com.sangjun.lab.jvmwarmup.product;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductController {
    private final ProductService service;
    public ProductController(ProductService service) { this.service = service; }
    @GetMapping("/api/products/{id}")
    public ResponseEntity<ProductResponse> find(@PathVariable long id) {
        return ResponseEntity.ok(service.find(id));
    }
}
