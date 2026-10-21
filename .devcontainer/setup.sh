#!/usr/bin/env bash
set -e

echo "=== 1. Instalando y configurando PostgreSQL nativo ==="
sudo apt-get update
sudo apt-get install -y postgresql postgresql-contrib
sudo service postgresql start

# Configurar contraseña 'postgres' y accesos locales sin fricción
sudo -u postgres psql -c "ALTER USER postgres WITH PASSWORD 'postgres';"
sudo -u postgres psql -c "CREATE DATABASE fineract_tenants;" || true
sudo -u postgres psql -c "CREATE DATABASE fineract_default;" || true

echo "=== 2. Optimizando Gradle para Codespace de 16GB ==="
mkdir -p ~/.gradle
cat << 'EOF' > ~/.gradle/gradle.properties
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8 --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.security=ALL-UNNAMED --add-opens=java.management/javax.management=ALL-UNNAMED --add-opens=java.naming/javax.naming=ALL-UNNAMED
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.vfs.watch=true
org.gradle.workers.max=3
EOF

echo "=== 3. Neutralizando bug de Eclipse p2.index en Spotless ==="
# Comentamos greclipse() en build.gradle para evitar que consulte download.eclipse.org/eclipse/updates/4.29
sed -i 's/greclipse()/\/\/ greclipse()/g' build.gradle
if [ -d "buildSrc" ]; then
  find buildSrc -name "*.gradle*" -exec sed -i 's/greclipse()/\/\/ greclipse()/g' {} +
fi

echo "=== Entorno listo ==="
