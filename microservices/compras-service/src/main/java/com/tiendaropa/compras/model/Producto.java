package com.tiendaropa.compras.model;

public record Producto(String id, String nombre, String categoria,
                       String talla, double precio, int stock) {}