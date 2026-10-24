package com.tiendaropa.catalogo.model;

public record Producto(String id, String nombre, String categoria,
                       String talla, double precio, int stock) {

    public Producto conStock(int nuevoStock) {
        return new Producto(id, nombre, categoria, talla, precio, nuevoStock);
    }
}