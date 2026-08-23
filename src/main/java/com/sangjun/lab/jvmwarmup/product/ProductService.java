package com.sangjun.lab.jvmwarmup.product;

import com.sangjun.lab.jvmwarmup.ConnectionHoldingQuery;
import org.springframework.stereotype.Service;

@Service
public class ProductService {
    private final ProductRepository repository;
    private final ConnectionHoldingQuery connectionHoldingQuery;
    public ProductService(ProductRepository repository, ConnectionHoldingQuery connectionHoldingQuery) {
        this.repository = repository; this.connectionHoldingQuery = connectionHoldingQuery;
    }
    public ProductResponse find(long id) {
        Product product = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("product not found: " + id));
        connectionHoldingQuery.selectThenHold(id);
        return ProductResponse.from(product);
    }
}
