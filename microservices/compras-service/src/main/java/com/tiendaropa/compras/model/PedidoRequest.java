package com.tiendaropa.compras.model;

public record PedidoRequest(String productoId, int cantidad, String cliente) {}