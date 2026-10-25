# Tienda de Ropa - Sistema de Microservicios con Monitoreo en Kubernetes

Sistema de microservicios para comercio electrónico de prendas de vestir desarrollado con **Java 17**, **Spring Boot 3.x**, instrumentado con **Micrometer & Prometheus** y desplegado sobre un clúster local de **Kubernetes (Minikube)** con monitoreo integral mediante **kube-prometheus-stack (Prometheus, Grafana y AlertManager)**.

---

## 📋 Tabla de Contenidos
- [Arquitectura del Sistema](#-arquitectura-del-sistema)
- [Estructura del Repositorio](#-estructura-del-repositorio)
- [Tecnologías Utilizadas](#-tecnologías-utilizadas)
- [Instalación de Herramientas](#-instalación-de-herramientas)
- [Puesta en Marcha Rápida](#-puesta-en-marcha-rápida)
- [URLs de Acceso](#-urls-de-acceso)
- [Endpoints de la API](#-endpoints-de-la-api)
- [Las 3 Casuísticas de Monitoreo](#-las-3-casuísticas-de-monitoreo)
- [Pruebas de Carga](#-pruebas-de-carga)
- [Limpieza del Entorno](#-limpieza-del-entorno)

---

## 🏛 Arquitectura del Sistema

El dominio modela el flujo de venta de una tienda de ropa mediante dos microservicios desacoplados:

1. **`catalogo-service` (Servicio A - Puerto 8081)**:
   - Administra el catálogo de productos (polos, jeans, casacas, calzado, accesorios).
   - Controla el inventario y procesa reservas atómicas de stock.
   - Soporta simulación de latencia artificial mediante la variable de entorno `CATALOGO_DELAY_MS`.
2. **`compras-service` (Servicio B - Puerto 8082)**:
   - Gestiona el ciclo de vida de los pedidos de compra de los clientes.
   - Para confirmar un pedido, consulta y reserva stock en `catalogo-service` vía HTTP usando **Spring WebClient** reactivo con timeout configurado (`CATALOGO_TIMEOUT_MS`).
   - Si el catálogo tarda más del tiempo límite o se cae, maneja el error de forma controlada (HTTP 503 Service Unavailable), registra la incidencia en logs y eleva métricas de fallas en Micrometer.

```
                           +---------------------------+
                           |     Prometheus Scrape     |
                           +-------------+-------------+
                                         |
                       +-----------------+-----------------+
                       |                                   |
                       v                                   v
             +--------------------+              +--------------------+
             |  compras-service   |              |  catalogo-service  |
  Client --->|    (Puerto 8082)   |---WebClient->|    (Puerto 8081)   |
             |                    |   (Timeout)  |                    |
             +--------------------+              +--------------------+
```

---

## 📁 Estructura del Repositorio

El proyecto cumple estrictamente con la estructura solicitada:

```text
tienda-ropa-monitoreo/
├── docs/
│   └── informe.md                # Informe técnico con partes A, B, C y D
├── k8s/
│   ├── app/
│   │   ├── namespace.yaml        # Namespace dedicado (tienda-ropa)
│   │   ├── catalogo.yaml         # Deployment, Service NodePort y Probes
│   │   └── compras.yaml          # Deployment, Service NodePort y Probes
│   └── monitoring/
│       ├── values.yaml           # Configuración personalizada de kube-prometheus-stack
│       ├── servicemonitors.yaml  # ServiceMonitors para catalogo y compras
│       ├── alertas.yaml          # PrometheusRules (4 alertas técnicas y de negocio)
│       └── dashboard.json        # Dashboard de Grafana con 6 paneles exportado
├── loadtesting/
│   └── stress.sh                 # Script de generación de tráfico parametrizable
├── microservices/
│   ├── catalogo-service/         # Código fuente Java 17, Maven y Dockerfile multi-stage
│   └── compras-service/          # Código fuente Java 17, Maven y Dockerfile multi-stage
├── scripts/
│   ├── setup.sh                  # Automatización de despliegue desde cero
│   └── teardown.sh               # Limpieza y desmontaje de recursos
└── README.md
```

---

## 🛠 Tecnologías Utilizadas

- **Lenguaje**: Java 17 (Eclipse Temurin)
- **Framework**: Spring Boot 3.2.x (Spring Web, Spring WebFlux / WebClient, Spring Boot Actuator)
- **Instrumentación y Métricas**: Micrometer con registro Prometheus (`/metrics`)
- **Contenedorización**: Docker con construcción multi-etapa (*multi-stage build*)
- **Orquestación**: Kubernetes v1.34+ (Minikube con driver Docker)
- **Gestor de Paquetes K8s**: Helm 3
- **Stack de Monitoreo**: `kube-prometheus-stack` (Prometheus Operator, Grafana, Alertmanager)

---

## 💻 Instalación de Herramientas

A continuación se detallan los comandos de instalación según el sistema operativo:

### En Windows (vía winget o Chocolatey / PowerShell como Administrador)

```powershell
# 1. Docker Desktop
winget install Docker.DockerDesktop

# 2. Minikube
winget install Kubernetes.minikube

# 3. kubectl
winget install Kubernetes.kubectl

# 4. Helm
winget install Helm.Helm

# 5. Git / curl (si no los tienes)
winget install Git.Git
```

### En Linux (Ubuntu / Debian)

```bash
# Docker Desktop / Engine
sudo apt-get update && sudo apt-get install -y docker.io
sudo usermod -aG docker $USER && newgrp docker

# Minikube
curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
sudo install minikube-linux-amd64 /usr/local/bin/minikube && rm minikube-linux-amd64

# kubectl
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl && rm kubectl

# Helm 3
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
```

---

## 🚀 Puesta en Marcha Rápida

El proyecto incluye un script de automatización completa que realiza todas las etapas sin intervención manual:

```bash
# Clonar el repositorio
git clone <URL_DE_TU_REPOSITORIO>
cd tienda-ropa-monitoreo

# Dar permisos de ejecución (en Linux/Git Bash) y ejecutar el setup
chmod +x scripts/*.sh loadtesting/*.sh
bash scripts/setup.sh
```

### ¿Qué realiza `scripts/setup.sh` automáticamente?
1. Verifica la presencia y ejecución de Docker, Minikube, kubectl y Helm.
2. Inicia Minikube asignando 4 vCPUs y 5120 MB de RAM y habilita el addon `metrics-server`.
3. Instala `kube-prometheus-stack` en el namespace `monitoring` usando `k8s/monitoring/values.yaml`.
4. Configura el entorno Docker local de Minikube (`eval $(minikube docker-env)`) y compila ambas imágenes de microservicios usando Dockerfiles multi-stage.
5. Crea el namespace `tienda-ropa` y despliega los Deployments y Services de ambos microservicios.
6. Aplica los `ServiceMonitors`, las reglas de alerta `PrometheusRule` y aprovisiona el dashboard de Grafana.
7. Fuerza el reload de Prometheus y establece port-forwards locales automáticos.

---

## 🌐 URLs de Acceso

Una vez ejecutado `scripts/setup.sh`, todos los componentes son accesibles directamente en tu máquina:

| Servicio | URL Local | Credenciales / Detalles |
| :--- | :--- | :--- |
| **Grafana** | `http://localhost:3000` | Usuario: `admin` \| Clave: `TiendaRopa2026!` (definida en `values.yaml`) |
| **Prometheus** | `http://localhost:9090` | Targets: `http://localhost:9090/targets`<br>Alertas: `http://localhost:9090/alerts` |
| **Catálogo Service** | `http://localhost:8081` | Health: `/health` \| Métricas: `/metrics` \| API: `/productos` |
| **Compras Service** | `http://localhost:8082` | Health: `/health` \| Métricas: `/metrics` \| API: `/pedidos` |

> **Nota:** Si estás en Minikube y prefieres acceder vía NodePort directo:
> - Minikube IP: `$(minikube ip)`
> - Grafana NodePort: `30300` (`http://$(minikube ip):30300`)
> - Prometheus NodePort: `30900` (`http://$(minikube ip):30900`)
> - Catálogo NodePort: `30081` (`http://$(minikube ip):30081`)
> - Compras NodePort: `30082` (`http://$(minikube ip):30082`)

---

## 📡 Endpoints de la API

### Catálogo Service (`:8081`)
- `GET /health`: Health check rápido e independiente.
- `GET /metrics`: Endpoint de métricas en formato estándar de Prometheus.
- `GET /productos`: Listado general de productos (soporta filtro opcional `?categoria=POLOS`).
- `GET /productos/{id}`: Detalle de un producto por ID.
- `GET /productos/bajo-stock?umbral=5`: Lista de prendas con inventario crítico.
- `POST /productos`: Registro de una nueva prenda de vestir.
- `POST /productos/{id}/reservar?cantidad=N`: Reserva atómica de inventario solicitada por compras.

### Compras Service (`:8082`)
- `GET /health`: Health check del servicio de compras.
- `GET /metrics`: Métricas de compras expuestas para Prometheus.
- `POST /pedidos`: Creación de un pedido (valida y reserva en catálogo vía WebClient).
- `GET /pedidos`: Listado histórico de pedidos procesados.
- `GET /pedidos/{id}`: Consulta de un pedido específico.
- `GET /pedidos/resumen`: Muestra el total de pedidos y monto acumulado en ventas en Soles (S/).
- `GET /tienda/catalogo`: Consulta delegada al catálogo a través de compras.

---

## 🧪 Las 3 Casuísticas de Monitoreo

### 🔴 Casuística 1: El Servicio Caído (CrashLoopBackOff)
- **Objetivo**: Demostrar el rol de los probes de Kubernetes y la detección inmediata de caídas en Prometheus y Grafana.
- **Activación**: Modificar el comando o alterar el puerto del health check para que el liveness probe falle:
  ```bash
  kubectl set env deployment/catalogo-service -n tienda-ropa BAD_CONFIG="crash"
  kubectl patch deployment catalogo-service -n tienda-ropa -p '{"spec":{"template":{"spec":{"containers":[{"name":"catalogo-service","livenessProbe":{"httpGet":{"path":"/ruta-rota"}}}]}}}}'
  ```
- **Diagnóstico**:
  - En `kubectl get pods -n tienda-ropa`, el pod entra en estado `CrashLoopBackOff` o falla las liveness probes.
  - En Prometheus `/targets`, el target de `catalogo-service` cambia a estado **DOWN**.
  - En Prometheus `/alerts`, la regla `CatalogoServiceCaido` pasa a estado **FIRING**.
  - En Grafana, el panel "Estado de Microservicios" cae a `0` (rojo).
- **Resolución**: Restaurar la configuración saludable del deployment:
  ```bash
  kubectl apply -f k8s/app/catalogo.yaml
  kubectl rollout status deployment/catalogo-service -n tienda-ropa
  ```

---

### 🟡 Casuística 2: El Servicio Lento (Propagación de Latencia y Timeouts)
- **Objetivo**: Simular degradación de rendimiento en el Servicio A (`catalogo-service`) que induce timeouts en el Servicio B (`compras-service`), evidenciando cómo la latencia se propaga.
- **Activación**: Inyectar un delay artificial de **3000 ms** (superando el timeout de 2000 ms de compras) y generar tráfico:
  ```bash
  # 1. Configurar delay de 3 segundos en catalogo-service
  kubectl set env deployment/catalogo-service -n tienda-ropa CATALOGO_DELAY_MS="3000"
  kubectl rollout status deployment/catalogo-service -n tienda-ropa

  # 2. Enviar tráfico continuo con el script de stress
  bash loadtesting/stress.sh -n 30 -d 0.5
  ```
- **Diagnóstico**:
  - `stress.sh` comienza a registrar respuestas `HTTP 503 (CATALOGO NO RESPONDE)`.
  - En Grafana, el Gauge `catalogo_delay_artificial_ms` muestra `3000 ms`.
  - El contador `compras_catalogo_errores_total` se incrementa rápidamente.
  - La alerta `CatalogoLentoOConTimeouts` cambia a estado **FIRING**.
- **Resolución**: Restablecer el delay artificial a 0 ms:
  ```bash
  kubectl set env deployment/catalogo-service -n tienda-ropa CATALOGO_DELAY_MS="0"
  kubectl rollout status deployment/catalogo-service -n tienda-ropa
  ```

---

### 🟠 Casuística 3: Agotamiento Crítico de Stock en Producto Estrella (Casaca de Cuero)
- **Objetivo**: Simular una casuística de negocio donde la alta demanda agota el inventario del producto estrella (`P003 - Casaca de cuero`, stock inicial: 5 unidades), provocando rechazos masivos de pedidos y pérdida de ventas.
- **Activación**: Ejecutar 15 pedidos continuos de la casaca de cuero:
  ```bash
  bash loadtesting/stress.sh -n 15 -p P003 -q 1 -d 0.5 -c "cliente-demanda"
  ```
- **Diagnóstico**:
  - Los primeros 5 pedidos responden `HTTP 201 (CONFIRMADO)`.
  - A partir del 6º pedido, responden `HTTP 409 (SIN STOCK)`.
  - La métrica de negocio `catalogo_reservas_rechazadas_total` sube vertiginosamente.
  - La alerta `PedidosRechazadosPorStock` y `StockTotalBajo` se activan en estado **FIRING**.
- **Resolución**: Reponer stock del producto consumiendo la API o reiniciando el servicio para recargar el stock en memoria:
  ```bash
  # Reposición mediante reinicio del pod del catálogo
  kubectl rollout restart deployment/catalogo-service -n tienda-ropa
  kubectl rollout status deployment/catalogo-service -n tienda-ropa
  ```

---

## ⚡ Pruebas de Carga

El script `loadtesting/stress.sh` permite generar tráfico parametrizado hacia la tienda:

```bash
# Sintaxis
bash loadtesting/stress.sh [-u URL] [-n PEDIDOS] [-p PRODUCTO] [-q CANTIDAD] [-d PAUSA_SEG] [-c CLIENTE]

# Ejemplos de uso:
# 50 pedidos regulares de polos
bash loadtesting/stress.sh -n 50 -p P001 -d 0.2

# 20 pedidos de pantalones con cliente VIP
bash loadtesting/stress.sh -n 20 -p P002 -q 2 -c "cliente-vip"
```

---

## 🧹 Limpieza del Entorno

Para desinstalar los microservicios, monitores y port-forwards:

```bash
bash scripts/teardown.sh
```

Si deseas desinstalar absolutamente todo, incluyendo la máquina virtual/nodo de Minikube:

```bash
bash scripts/teardown.sh --cluster
```

---
*Proyecto individual para el Taller de Monitoreo Kubernetes — Valle Grande.*
