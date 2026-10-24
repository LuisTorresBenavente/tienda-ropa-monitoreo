package com.tiendaropa.compras.controller;

import com.tiendaropa.compras.model.Pedido;
import com.tiendaropa.compras.model.PedidoRequest;
import com.tiendaropa.compras.model.Producto;
import com.tiendaropa.compras.service.ComprasService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class ComprasController {

    private final ComprasService service;

    public ComprasController(ComprasService service) {
        this.service = service;
    }

    @PostMapping("/pedidos")
    public ResponseEntity<?> crear(@RequestBody PedidoRequest req) {
        ComprasService.Resultado r = service.crearPedido(req);
        return switch (r.estado()) {
            case CONFIRMADO -> ResponseEntity.status(HttpStatus.CREATED).body(r.pedido());
            case INVALIDO -> ResponseEntity.badRequest()
                    .body(Map.of("error", "Pedido inválido: productoId y cantidad > 0 son obligatorios"));
            case PRODUCTO_NO_EXISTE -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "El producto no existe en el catálogo"));
            case SIN_STOCK -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Stock insuficiente para completar el pedido"));
            case CATALOGO_NO_DISPONIBLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "El catálogo no responde. Intenta nuevamente en unos instantes"));
        };
    }

    @GetMapping("/pedidos")
    public List<Pedido> listar() {
        return service.listar();
    }

    @GetMapping("/pedidos/resumen")
    public Map<String, Object> resumen() {
        return service.resumen();
    }

    @GetMapping("/pedidos/{id}")
    public ResponseEntity<?> obtener(@PathVariable String id) {
        Pedido p = service.obtener(id);
        return p == null
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Pedido no encontrado: " + id))
                : ResponseEntity.ok(p);
    }

    @GetMapping("/tienda/catalogo")
    public ResponseEntity<?> catalogo() {
        List<Producto> productos = service.catalogoDisponible();
        return productos == null
                ? ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "El catálogo no responde"))
                : ResponseEntity.ok(productos);
    }
}