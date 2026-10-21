#!/usr/bin/env bash
set -e

echo "=== 1. Instalando y configurando PostgreSQL nativo ==="
sudo rm -f /etc/apt/sources.list.d/yarn.list
sudo apt-get update
sudo apt-get install -y postgresql postgresql-contrib
sudo service postgresql start

sudo -u postgres psql -c "ALTER USER postgres WITH PASSWORD 'postgres';"
sudo -u postgres psql -c "CREATE DATABASE fineract_tenants;" || true
sudo -u postgres psql -c "CREATE DATABASE fineract_default;" || true

echo "=== 2. Ajustando memoria de Gradle de 12GB a 4GB ==="
sed -i 's/-Xmx12g/-Xmx4g -XX:MaxMetaspaceSize=1g/g' gradle.properties
sed -i 's/-Xmx[0-9]\+g/-Xmx4g/g' gradle.properties

echo "=== 3. Desactivando formateador de Eclipse caído (Spotless) ==="
sed -i 's/greclipse()/\/\/ greclipse()/g' build.gradle
if [ -d "buildSrc" ]; then
  find buildSrc -name "*.gradle*" -exec sed -i 's/greclipse()/\/\/ greclipse()/g' {} +
fi

echo "=== Configuración base terminada con éxito ==="
