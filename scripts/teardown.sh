#!/usr/bin/env bash
# =============================================================================
# teardown.sh - Elimina los recursos del proyecto tienda-ropa.
#
# Uso:
#   bash scripts/teardown.sh             # borra apps + monitoreo, conserva Minikube
#   bash scripts/teardown.sh --cluster   # ademas elimina el cluster Minikube completo
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PID_FILE="$ROOT/scripts/.portforward.pids"
DELETE_CLUSTER=false

for arg in "$@"; do
  case "$arg" in
    --cluster|-c) DELETE_CLUSTER=true ;;
    -h|--help)
      sed -n '2,9p' "$0"
      exit 0 ;;
    *)
      echo "Argumento desconocido: $arg"
      echo "Uso: bash scripts/teardown.sh [--cluster]"
      exit 1 ;;
  esac
done

log() { echo -e "\n\033[1;34m==> $*\033[0m"; }
ok()  { echo -e "\033[1;32m[OK]\033[0m $*"; }

# -----------------------------------------------------------------------------
log "Cerrando port-forwards"
if [ -f "$PID_FILE" ]; then
  while read -r pid; do
    [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  done < "$PID_FILE"
  rm -f "$PID_FILE"
fi
ok "Port-forwards cerrados"

# -----------------------------------------------------------------------------
if command -v kubectl >/dev/null 2>&1 && kubectl cluster-info >/dev/null 2>&1; then
  log "Eliminando recursos de Kubernetes"
  kubectl delete -f k8s/monitoring/alertas.yaml        --ignore-not-found
  kubectl delete -f k8s/monitoring/servicemonitors.yaml --ignore-not-found
  kubectl delete configmap tienda-ropa-dashboard -n monitoring --ignore-not-found
  kubectl delete -f k8s/app/compras.yaml               --ignore-not-found
  kubectl delete -f k8s/app/catalogo.yaml              --ignore-not-found
  kubectl delete namespace tienda-ropa                 --ignore-not-found

  if command -v helm >/dev/null 2>&1; then
    helm uninstall monitoring -n monitoring 2>/dev/null || true
  fi
  kubectl delete namespace monitoring --ignore-not-found
  ok "Recursos eliminados"
else
  echo "No hay cluster accesible, se omite la limpieza de Kubernetes."
fi

# -----------------------------------------------------------------------------
if [ "$DELETE_CLUSTER" = true ]; then
  log "Eliminando el cluster Minikube completo"
  minikube delete
  ok "Cluster eliminado"
else
  echo
  echo "El cluster Minikube se conserva. Usa --cluster para eliminarlo tambien."
fi