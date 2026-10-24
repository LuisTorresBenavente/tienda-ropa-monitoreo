package com.tiendaropa.catalogo.service;

import com.tiendaropa.catalogo.model.Producto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CatalogoService {

    private static final Logger log = LoggerFactory.getLogger(CatalogoService.class);

    public enum Resultado { OK, NO_ENCONTRADO, SIN_STOCK }
    public record Reserva(Resultado resultado, Producto producto) {}

    private final Map<String, Producto> productos = new ConcurrentHashMap<>();
    private final long delayMs;

    // Métricas
    private final Counter consultas;      // Counter
    private final Counter reservasOk;     // Counter
    private final Counter reservasFail;   // Counter
    private final Timer duracionConsulta; // Timer

    public CatalogoService(MeterRegistry registry,
                           @Value("${catalogo.delay-ms:0}") long delayMs) {
        this.delayMs = delayMs;

        consultas = Counter.builder("catalogo.productos.consultados")
                .description("Total de consultas de productos").register(registry);
        reservasOk = Counter.builder("catalogo.reservas.exitosas")
                .description("Reservas de stock exitosas").register(registry);
        reservasFail = Counter.builder("catalogo.reservas.rechazadas")
                .description("Reservas rechazadas por falta de stock").register(registry);
        duracionConsulta = Timer.builder("catalogo.consulta.duracion")
                .description("Duración de las consultas al catálogo")
                .publishPercentileHistogram().register(registry);

        Gauge.builder("catalogo.productos.total", productos, Map::size)
                .description("Cantidad de productos en el catálogo").register(registry);
        Gauge.builder("catalogo.stock.total", productos,
                        m -> m.values().stream().mapToInt(Producto::stock).sum())
                .description("Unidades totales en stock").register(registry);
        Gauge.builder("catalogo.delay.artificial.ms", this, s -> s.delayMs)
                .description("Delay artificial configurado (ms)").register(registry);

        cargarDatosIniciales();
        log.info("catalogo-service iniciado. Delay artificial = {} ms", delayMs);
    }

    private void cargarDatosIniciales() {
        guardar(new Producto("P001", "Polo básico algodón", "POLOS", "M", 49.90, 50));
        guardar(new Producto("P002", "Jean slim fit", "PANTALONES", "32", 129.90, 30));
        guardar(new Producto("P003", "Casaca de cuero", "CASACAS", "L", 299.90, 5));
        guardar(new Producto("P004", "Zapatillas urbanas", "CALZADO", "42", 189.90, 20));
        guardar(new Producto("P005", "Gorra deportiva", "ACCESORIOS", "U", 35.00, 100));
    }

    private void guardar(Producto p) { productos.put(p.id(), p); }

    private void simularDelay() {
        if (delayMs > 0) {
            try { Thread.sleep(delayMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public List<Producto> listar(String categoria) {
        return duracionConsulta.record(() -> {
            simularDelay();
            consultas.increment();
            return productos.values().stream()
                    .filter(p -> categoria == null || p.categoria().equalsIgnoreCase(categoria))
                    .toList();
        });
    }

    public Optional<Producto> obtener(String id) {
        return duracionConsulta.record(() -> {
            simularDelay();
            consultas.increment();
            return Optional.ofNullable(productos.get(id));
        });
    }

    public List<Producto> bajoStock(int umbral) {
        return productos.values().stream().filter(p -> p.stock() <= umbral).toList();
    }

    public Producto crear(Producto nuevo) {
        String id = (nuevo.id() == null || nuevo.id().isBlank())
                ? "P" + UUID.randomUUID().toString().substring(0, 6).toUpperCase()
                : nuevo.id();
        Producto p = new Producto(id, nuevo.nombre(), nuevo.categoria(),
                nuevo.talla(), nuevo.precio(), nuevo.stock());
        guardar(p);
        return p;
    }

    public synchronized Reserva reservar(String id, int cantidad) {
        return duracionConsulta.record(() -> {
            simularDelay();
            Producto p = productos.get(id);
            if (p == null) return new Reserva(Resultado.NO_ENCONTRADO, null);
            if (p.stock() < cantidad) {
                reservasFail.increment();
                return new Reserva(Resultado.SIN_STOCK, p);
            }
            Producto actualizado = p.conStock(p.stock() - cantidad);
            guardar(actualizado);
            reservasOk.increment();
            return new Reserva(Resultado.OK, actualizado);
        });
    }
}