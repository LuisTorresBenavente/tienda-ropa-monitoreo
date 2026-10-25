#!/usr/bin/env bash
# =============================================================================
# setup.sh - Levanta TODO el sistema tienda-ropa desde cero, sin intervencion.
#
# Uso:   bash scripts/setup.sh
# Opcional (variables de entorno): CPUS=4 MEMORY=5120 K8S_VERSION=v1.34.0
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CPUS="${CPUS:-4}"
MEMORY="${MEMORY:-5120}"
K8S_VERSION="${K8S_VERSION:-v1.34.0}"
NS_APP="tienda-ropa"
NS_MON="monitoring"
PID_FILE="$ROOT/scripts/.portforward.pids"

log()  { echo -e "\n\033[1;34m==> $*\033[0m"; }
ok()   { echo -e "\033[1;32m[OK]\033[0m $*"; }
warn() { echo -e "\033[1;33m[AVISO]\033[0m $*"; }
fail() { echo -e "\033[1;31m[ERROR]\033[0m $*" >&2; exit 1; }

# -----------------------------------------------------------------------------
log "1/8 Verificando prerequisitos"
for cmd in docker minikube kubectl helm curl; do
  command -v "$cmd" >/dev/null 2>&1 || fail "Falta '$cmd'. Revisa la seccion de instalacion del README."
  ok "$cmd encontrado"
done
docker info >/dev/null 2>&1 || fail "Docker no responde. Abre Docker Desktop y espera a 'Engine running'."
ok "Docker esta corriendo"

# -----------------------------------------------------------------------------
log "2/8 Iniciando Minikube (cpus=$CPUS, memoria=${MEMORY}MB, k8s=$K8S_VERSION)"
if minikube status >/dev/null 2>&1; then
  ok "Minikube ya estaba corriendo"
else
  minikube start --driver=docker --container-runtime=docker \
    --cpus="$CPUS" --memory="$MEMORY" --kubernetes-version="$K8S_VERSION"
fi
minikube addons enable metrics-server >/dev/null
ok "Addon metrics-server habilitado"
kubectl config use-context minikube >/dev/null
kubectl wait --for=condition=Ready node/minikube --timeout=180s >/dev/null
ok "Nodo minikube en estado Ready"

# -----------------------------------------------------------------------------
log "3/8 Instalando kube-prometheus-stack con Helm (puede tardar varios minutos)"
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo update >/dev/null
kubectl create namespace "$NS_MON" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
helm upgrade --install monitoring prometheus-community/kube-prometheus-stack \
  -n "$NS_MON" -f k8s/monitoring/values.yaml --wait --timeout 10m >/dev/null
ok "Stack de monitoreo instalado"

# -----------------------------------------------------------------------------
log "4/8 Construyendo imagenes dentro del Docker de Minikube"
build_image() {
  local name="$1" dir="$2"
  docker build -t "$name:1.0.0" "$dir"
}
if eval "$(minikube -p minikube docker-env --shell bash)" 2>/dev/null; then
  build_image catalogo-service microservices/catalogo-service
  build_image compras-service  microservices/compras-service
else
  warn "docker-env no disponible, usando 'minikube image build'"
  minikube image build -t catalogo-service:1.0.0 microservices/catalogo-service
  minikube image build -t compras-service:1.0.0  microservices/compras-service
fi
ok "Imagenes construidas"

# -----------------------------------------------------------------------------
log "5/8 Desplegando microservicios"
kubectl apply -f k8s/app/namespace.yaml
kubectl apply -f k8s/app/catalogo.yaml
kubectl apply -f k8s/app/compras.yaml
kubectl rollout status deployment/catalogo-service -n "$NS_APP" --timeout=180s
kubectl rollout status deployment/compras-service  -n "$NS_APP" --timeout=180s
ok "Pods de tienda-ropa en Running"

# -----------------------------------------------------------------------------
log "6/8 Aplicando ServiceMonitors, alertas y dashboard"
kubectl apply -f k8s/monitoring/servicemonitors.yaml
kubectl apply -f k8s/monitoring/alertas.yaml
kubectl create configmap tienda-ropa-dashboard -n "$NS_MON" \
  --from-file=tienda-ropa-dashboard.json=k8s/monitoring/dashboard.json \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label configmap tienda-ropa-dashboard -n "$NS_MON" grafana_dashboard=1 --overwrite >/dev/null
ok "Monitores, alertas y dashboard aplicados"

# Reiniciar Prometheus fuerza la recarga inmediata de ServiceMonitors y reglas.
log "7/8 Recargando Prometheus"
kubectl rollout restart statefulset/prometheus-monitoring-kube-prometheus-prometheus -n "$NS_MON" >/dev/null
sleep 5
kubectl rollout status statefulset/prometheus-monitoring-kube-prometheus-prometheus -n "$NS_MON" --timeout=300s
ok "Prometheus recargado"

# -----------------------------------------------------------------------------
log "8/8 Abriendo accesos locales (port-forward en segundo plano)"
: > "$PID_FILE"
start_pf() {
  local ns="$1" svc="$2" lport="$3" rport="$4"
  if (echo >"/dev/tcp/127.0.0.1/$lport") 2>/dev/null; then
    warn "El puerto $lport ya esta en uso, se omite $svc"
    return 0
  fi
  nohup kubectl port-forward -n "$ns" "svc/$svc" "$lport:$rport" >/dev/null 2>&1 &
  echo $! >> "$PID_FILE"
}
start_pf "$NS_MON" monitoring-grafana                     3000 80
start_pf "$NS_MON" monitoring-kube-prometheus-prometheus  9090 9090
start_pf "$NS_APP" catalogo-service                       8081 8081
start_pf "$NS_APP" compras-service                        8082 8082
sleep 4

GRAFANA_PASS="$(grep -E 'adminPassword' k8s/monitoring/values.yaml | head -1 \
  | sed -E 's/.*adminPassword:[[:space:]]*"([^"]*)".*/\1/')"
[ -n "$GRAFANA_PASS" ] || GRAFANA_PASS="(ver k8s/monitoring/values.yaml)"

echo
echo "=============================================================="
echo "  SISTEMA LISTO"
echo "=============================================================="
echo "  Grafana     : http://localhost:3000   usuario: admin  clave: $GRAFANA_PASS"
echo "  Prometheus  : http://localhost:9090   (targets en /targets, alertas en /alerts)"
echo "  catalogo    : http://localhost:8081   (/health, /productos, /metrics)"
echo "  compras     : http://localhost:8082   (/health, /pedidos, /tienda/catalogo, /metrics)"
echo "--------------------------------------------------------------"
echo "  Prueba rapida : bash loadtesting/stress.sh -n 10"
echo "  Apagar todo   : bash scripts/teardown.sh   (agrega --cluster para borrar Minikube)"
echo "=============================================================="