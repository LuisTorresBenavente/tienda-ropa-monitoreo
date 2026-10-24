package com.tiendaropa.compras.service;

import com.tiendaropa.compras.model.Pedido;
import com.tiendaropa.compras.model.PedidoRequest;
import com.tiendaropa.compras.model.Producto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ComprasService {

    private static final Logger log = LoggerFactory.getLogger(ComprasService.class);

    public enum Estado { CONFIRMADO, SIN_STOCK, PRODUCTO_NO_EXISTE, CATALOGO_NO_DISPONIBLE, INVALIDO }
    public record Resultado(Estado estado, Pedido pedido, Integer stockDisponible) {}
    private record RespuestaCatalogo(int status, Producto producto) {}

    private final WebClient catalogo;
    private final long timeoutMs;
    private final MeterRegistry registry;
    private final List<Pedido> pedidos = new CopyOnWriteArrayList<>();

    private final Counter pedidosConfirmados;
    private final Counter catalogoErrores;
    private final Timer llamadaCatalogo;

    public ComprasService(WebClient catalogoWebClient,
                          @Value("${catalogo.timeout-ms}") long timeoutMs,
                          MeterRegistry registry) {
        this.catalogo = catalogoWebClient;
        this.timeoutMs = timeoutMs;
        this.registry = registry;

        pedidosConfirmados = Counter.builder("compras.pedidos.confirmados")
                .description("Pedidos confirmados").register(registry);
        catalogoErrores = Counter.builder("compras.catalogo.errores")
                .description("Fallos o timeouts al llamar a catalogo-service").register(registry);
        llamadaCatalogo = Timer.builder("compras.catalogo.llamada.duracion")
                .description("Duración de la llamada a catalogo-service")
                .publishPercentileHistogram().register(registry);

        Gauge.builder("compras.pedidos.total", pedidos, List::size)
                .description("Pedidos registrados en memoria").register(registry);
        Gauge.builder("compras.ventas.total.soles", pedidos,
                        l -> l.stream().filter(p -> "CONFIRMADO".equals(p.estado()))
                                .mapToDouble(Pedido::total).sum())
                .description("Monto total vendido (S/)").register(registry);
        Gauge.builder("compras.catalogo.timeout.ms", this, s -> s.timeoutMs)
                .description("Timeout configurado hacia catalogo (ms)").register(registry);

        log.info("compras-service iniciado. Timeout hacia catalogo = {} ms", timeoutMs);
    }

    public Resultado crearPedido(PedidoRequest req) {
        if (req == null || req.productoId() == null || req.cantidad() <= 0) {
            return rechazo("invalido", Estado.INVALIDO, null);
        }

        RespuestaCatalogo r = llamarCatalogo(req.productoId(), req.cantidad());

        if (r == null) {
            return rechazo("catalogo_no_disponible", Estado.CATALOGO_NO_DISPONIBLE, null);
        }
        if (r.status() == 404) {
            return rechazo("producto_no_existe", Estado.PRODUCTO_NO_EXISTE, null);
        }
        if (r.status() == 409) {
            return rechazo("sin_stock", Estado.SIN_STOCK, null);
        }
        if (r.producto() == null) {
            return rechazo("catalogo_no_disponible", Estado.CATALOGO_NO_DISPONIBLE, null);
        }

        Producto p = r.producto();
        Pedido pedido = new Pedido("PED-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                req.cliente(), p.id(), p.nombre(), req.cantidad(),
                p.precio() * req.cantidad(), "CONFIRMADO", Instant.now().toString());
        pedidos.add(pedido);
        pedidosConfirmados.increment();
        return new Resultado(Estado.CONFIRMADO, pedido, null);
    }

    /** Devuelve null si catálogo no responde a tiempo o falla. */
    private RespuestaCatalogo llamarCatalogo(String productoId, int cantidad) {
        return llamadaCatalogo.record(() -> {
            try {
                return catalogo.post()
                        .uri("/productos/{id}/reservar?cantidad={c}", productoId, cantidad)
                        .exchangeToMono(resp -> {
                            if (resp.statusCode().is2xxSuccessful()) {
                                return resp.bodyToMono(Producto.class)
                                        .map(prod -> new RespuestaCatalogo(200, prod));
                            }
                            return resp.releaseBody()
                                    .thenReturn(new RespuestaCatalogo(resp.statusCode().value(), null));
                        })
                        .timeout(Duration.ofMillis(timeoutMs))
                        .block();
            } catch (Exception e) {
                catalogoErrores.increment();
                log.error("Fallo al consultar catalogo-service (timeout={} ms, producto={}): {}",
                        timeoutMs, productoId, e.toString());
                return null;
            }
        });
    }

    private Resultado rechazo(String motivo, Estado estado, Integer stock) {
        registry.counter("compras.pedidos.rechazados", "motivo", motivo).increment();
        return new Resultado(estado, null, stock);
    }

    public List<Pedido> listar() { return List.copyOf(pedidos); }

    public Pedido obtener(String id) {
        return pedidos.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    public Map<String, Object> resumen() {
        double ventas = pedidos.stream().mapToDouble(Pedido::total).sum();
        return Map.of("totalPedidos", pedidos.size(), "ventasTotalesSoles", ventas);
    }

    public List<Producto> catalogoDisponible() {
        try {
            return catalogo.get().uri("/productos")
                    .retrieve().bodyToFlux(Producto.class)
                    .collectList()
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();
        } catch (Exception e) {
            catalogoErrores.increment();
            log.error("Fallo al listar catalogo: {}", e.toString());
            return null;
        }
    }
}