package com.sangjun.lab.jvmwarmup.product;
public record ProductResponse(Long id, String name) {
    static ProductResponse from(Product product) { return new ProductResponse(product.getId(), product.getName()); }
}
