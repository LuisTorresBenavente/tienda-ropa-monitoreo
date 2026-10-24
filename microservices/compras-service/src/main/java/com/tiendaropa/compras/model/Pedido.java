package com.tiendaropa.compras.model;

public record Pedido(String id, String cliente, String productoId, String nombreProducto,
                     int cantidad, double total, String estado, String fecha) {}