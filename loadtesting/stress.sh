#!/usr/bin/env bash
# =============================================================================
# stress.sh - Genera trafico de pedidos hacia compras-service.
#
# Uso:
#   bash loadtesting/stress.sh [-u URL] [-n PEDIDOS] [-p PRODUCTO] [-q CANTIDAD] [-d SEGUNDOS] [-c CLIENTE]
#
# Opciones (valores por defecto):
#   -u  URL base de compras-service        (http://localhost:8082)
#   -n  numero de pedidos a enviar         (50)
#   -p  id del producto a pedir            (P001)
#   -q  cantidad por pedido                (1)
#   -d  pausa entre pedidos en segundos    (0.5)
#   -c  nombre del cliente                 (stress)
#
# Ejemplos por casuistica:
#   Casuistica 2 (servicio lento): bash loadtesting/stress.sh -n 40 -d 0.5
#   Casuistica 3 (stock agotado) : bash loadtesting/stress.sh -n 15 -p P003 -d 1
# =============================================================================
set -u

URL="http://localhost:8082"
N=50
PRODUCTO="P001"
QTY=1
DELAY="0.5"
CLIENTE="stress"

usage() { sed -n '2,20p' "$0"; exit "${1:-0}"; }

while getopts "u:n:p:q:d:c:h" opt; do
  case "$opt" in
    u) URL="$OPTARG" ;;
    n) N="$OPTARG" ;;
    p) PRODUCTO="$OPTARG" ;;
    q) QTY="$OPTARG" ;;
    d) DELAY="$OPTARG" ;;
    c) CLIENTE="$OPTARG" ;;
    h) usage 0 ;;
    *) usage 1 ;;
  esac
done

command -v curl >/dev/null 2>&1 || { echo "Falta curl"; exit 1; }

BODY="{\"productoId\":\"$PRODUCTO\",\"cantidad\":$QTY,\"cliente\":\"$CLIENTE\"}"

echo "Enviando $N pedidos a $URL/pedidos (producto=$PRODUCTO, cantidad=$QTY, pausa=${DELAY}s)"
echo "--------------------------------------------------------------"

ok=0; sin_stock=0; no_disponible=0; otros=0
inicio=$SECONDS

for i in $(seq 1 "$N"); do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 -X POST "$URL/pedidos" \
          -H 'Content-Type: application/json' -d "$BODY" || true)"
  case "$code" in
    201) ok=$((ok + 1));                       etiqueta="CONFIRMADO" ;;
    409) sin_stock=$((sin_stock + 1));         etiqueta="SIN STOCK" ;;
    503) no_disponible=$((no_disponible + 1)); etiqueta="CATALOGO NO RESPONDE" ;;
    *)   otros=$((otros + 1));                 etiqueta="OTRO ($code)" ;;
  esac
  echo "[$i/$N] HTTP $code  $etiqueta"
  sleep "$DELAY"
done

echo "--------------------------------------------------------------"
echo "Resumen en $((SECONDS - inicio)) s:"
echo "  Confirmados (201)        : $ok"
echo "  Sin stock (409)          : $sin_stock"
echo "  Catalogo no responde (503): $no_disponible"
echo "  Otros / sin conexion     : $otros"