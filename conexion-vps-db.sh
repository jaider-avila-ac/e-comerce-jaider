#!/bin/bash
# Abre un túnel SSH hacia la base de datos de calzacaribe en el VPS (Coolify),
# que no tiene puerto público expuesto a propósito.
#
# Deja este script corriendo en una terminal mientras desarrollas localmente contra
# la base del VPS. El backend (ecommerce/.env) apunta a host.docker.internal:5433,
# que llega hasta acá.
#
# Requiere la llave SSH privada del VPS (ajusta la ruta si cambia de ubicación).

SSH_KEY="C:/Users/jaide/Videos/hello/zampy_vps"
VPS_HOST="2.25.178.224"
VPS_PORT=22
CONTAINER_IP="172.16.1.10"   # IP interna del contenedor calzacaribe-postgresql-database en la red 'coolify'
LOCAL_PORT=5433

echo "Abriendo túnel localhost:$LOCAL_PORT -> $CONTAINER_IP:5432 (vía $VPS_HOST)..."
ssh -i "$SSH_KEY" -p "$VPS_PORT" \
  -o ServerAliveInterval=30 -o ExitOnForwardFailure=yes \
  -N -L "0.0.0.0:${LOCAL_PORT}:${CONTAINER_IP}:5432" \
  root@"$VPS_HOST"
