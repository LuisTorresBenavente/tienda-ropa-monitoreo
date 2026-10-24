package com.tiendaropa.catalogo.controller;

import com.tiendaropa.catalogo.model.Producto;
import com.tiendaropa.catalogo.service.CatalogoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/productos")
public class CatalogoController {

    private final CatalogoService service;

    public CatalogoController(CatalogoService service) {
        this.service = service;
    }

    // 1. Listar productos (opcionalmente por categoría)
    @GetMapping
    public List<Producto> listar(@RequestParam(required = false) String categoria) {
        return service.listar(categoria);
    }

    // 2. Obtener un producto
    @GetMapping("/{id}")
    public ResponseEntity<?> obtener(@PathVariable String id) {
        return service.obtener(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Producto no encontrado: " + id)));
    }

    // 3. Productos con stock bajo
    @GetMapping("/bajo-stock")
    public List<Producto> bajoStock(@RequestParam(defaultValue = "5") int umbral) {
        return service.bajoStock(umbral);
    }

    // 4. Crear producto
    @PostMapping
    public ResponseEntity<Producto> crear(@RequestBody Producto producto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.crear(producto));
    }

    // 5. Reservar stock (lo usará compras-service)
    @PostMapping("/{id}/reservar")
    public ResponseEntity<?> reservar(@PathVariable String id,
                                      @RequestParam int cantidad) {
        CatalogoService.Reserva r = service.reservar(id, cantidad);
        return switch (r.resultado()) {
            case OK -> ResponseEntity.ok(r.producto());
            case NO_ENCONTRADO -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Producto no encontrado: " + id));
            case SIN_STOCK -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Stock insuficiente", "stockDisponible", r.producto().stock()));
        };
    }
}