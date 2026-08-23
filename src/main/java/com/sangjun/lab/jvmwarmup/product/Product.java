package com.sangjun.lab.jvmwarmup.product;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    protected Product() {}
    public Product(String name) { this.name = name; }
    public Long getId() { return id; }
    public String getName() { return name; }
}
