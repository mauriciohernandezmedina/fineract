#!/usr/bin/env bash
set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
APT_OPTS=("-y" "-o" "Dpkg::Options::=--force-confdef" "-o" "Dpkg::Options::=--force-confold")

echo "=== 0. Asegurando tags de Git en clon superficial (requerido por git-versioning) ==="
git fetch --depth=25 origin +refs/tags/1.15.0:refs/tags/1.15.0 2>/dev/null || true
if ! git describe --tags --match "*[0-9].[0-9]*.[0-9]*" >/dev/null 2>&1; then
  git tag -f 1.15.0 HEAD
fi

echo "=== 1. Instalando PostgreSQL 18 (modo no interactivo) ==="
if ! dpkg -s postgresql-18 >/dev/null 2>&1; then
  sudo rm -f /etc/apt/sources.list.d/yarn.list
  sudo DEBIAN_FRONTEND=noninteractive apt-get update -y
  sudo DEBIAN_FRONTEND=noninteractive apt-get install "${APT_OPTS[@]}" curl ca-certificates gnupg postgresql-common

  if [ -x /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh ]; then
    sudo DEBIAN_FRONTEND=noninteractive YES=yes /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh -y < /dev/null
    sudo DEBIAN_FRONTEND=noninteractive apt-get update -y
    sudo DEBIAN_FRONTEND=noninteractive apt-get install "${APT_OPTS[@]}" postgresql-18 postgresql-client-18
  else
    sudo DEBIAN_FRONTEND=noninteractive apt-get install "${APT_OPTS[@]}" postgresql postgresql-contrib
  fi
else
  echo "PostgreSQL 18 ya está instalado."
fi

# Configurar pg_hba.conf para permitir conexiones locales por TCP y socket con trust
PG_HBA_FILES=$(sudo find /etc/postgresql -type f -name "pg_hba.conf" 2>/dev/null || true)
for hba in $PG_HBA_FILES; do
  sudo sed -i -E 's/(peer|md5|scram-sha-256)/trust/g' "$hba"
done

sudo service postgresql restart

echo "=== 2. Inicializando roles (postgres y root) y bases de datos de Fineract ==="
psql -h 127.0.0.1 -U postgres -v ON_ERROR_STOP=1 -c "ALTER USER postgres WITH SUPERUSER PASSWORD 'postgres';"
psql -h 127.0.0.1 -U postgres -tc "SELECT 1 FROM pg_roles WHERE rolname = 'root'" | grep -q 1 \
  && psql -h 127.0.0.1 -U postgres -c "ALTER ROLE root WITH SUPERUSER LOGIN PASSWORD 'postgres';" \
  || psql -h 127.0.0.1 -U postgres -c "CREATE ROLE root WITH SUPERUSER LOGIN PASSWORD 'postgres';"

psql -h 127.0.0.1 -U postgres -tc "SELECT 1 FROM pg_database WHERE datname = 'fineract_tenants'" | grep -q 1 \
  || psql -h 127.0.0.1 -U postgres -c "CREATE DATABASE fineract_tenants OWNER postgres;"
psql -h 127.0.0.1 -U postgres -tc "SELECT 1 FROM pg_database WHERE datname = 'fineract_default'" | grep -q 1 \
  || psql -h 127.0.0.1 -U postgres -c "CREATE DATABASE fineract_default OWNER postgres;"

psql -h 127.0.0.1 -U postgres -d fineract_tenants -c "GRANT ALL ON SCHEMA public TO postgres, root;"
psql -h 127.0.0.1 -U postgres -d fineract_default -c "GRANT ALL ON SCHEMA public TO postgres, root;"

echo "=== 3. Configurando Gradle 8.14.5 y JDK 21 para Codespaces (16GB RAM / 4 vCPU) ==="
mkdir -p ~/.gradle/init.d

cat << 'EOF' > ~/.gradle/gradle.properties
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -XX:+UseParallelGC -Dfile.encoding=UTF-8 --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED --add-exports=java.naming/com.sun.jndi.ldap=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.security=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.management/javax.management=ALL-UNNAMED --add-opens=java.naming/javax.naming=ALL-UNNAMED --add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.vfs.watch=true
org.gradle.workers.max=2
org.gradle.java.installations.auto-download=false
org.gradle.java.installations.paths=/usr/lib/jvm/msopenjdk-current
EOF

cat << 'EOF' > ~/.gradle/init.d/codespaces.gradle
allprojects {
    tasks.configureEach { task ->
        if (task.name.toLowerCase().contains("spotlessgroovygradle")) {
            task.enabled = false
        }
    }
    tasks.withType(JavaExec).configureEach { task ->
        if (task.name in ['bootRun', 'devRun']) {
            jvmArgs(
                '-Xmx2g',
                '-XX:MaxMetaspaceSize=512m',
                '-XX:TieredStopAtLevel=1',
                '-XX:+UseStringDeduplication',
                '-Dfile.encoding=UTF-8',
                '-Duser.timezone=UTC',
                '-Djava.security.egd=file:/dev/./urandom',
                '--add-exports=java.naming/com.sun.jndi.ldap=ALL-UNNAMED',
                '--add-opens=java.base/java.lang=ALL-UNNAMED',
                '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED',
                '--add-opens=java.base/java.io=ALL-UNNAMED',
                '--add-opens=java.base/java.security=ALL-UNNAMED',
                '--add-opens=java.base/java.util=ALL-UNNAMED',
                '--add-opens=java.management/javax.management=ALL-UNNAMED',
                '--add-opens=java.naming/javax.naming=ALL-UNNAMED'
            )
        }
    }
    tasks.withType(Test).configureEach {
        maxParallelForks = 1
        jvmArgs(
            '-Xmx1536m',
            '-XX:MaxMetaspaceSize=512m',
            '-XX:TieredStopAtLevel=1',
            '-Dfile.encoding=UTF-8',
            '-Duser.timezone=UTC',
            '--add-exports=java.naming/com.sun.jndi.ldap=ALL-UNNAMED',
            '--add-opens=java.base/java.lang=ALL-UNNAMED',
            '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED',
            '--add-opens=java.base/java.io=ALL-UNNAMED',
            '--add-opens=java.base/java.security=ALL-UNNAMED',
            '--add-opens=java.base/java.util=ALL-UNNAMED',
            '--add-opens=java.management/javax.management=ALL-UNNAMED',
            '--add-opens=java.naming/javax.naming=ALL-UNNAMED'
        )
    }
}
EOF

echo "=== 4. Ajustando archivos locales de Gradle sin ensuciar git status ==="
sed -i 's/-Xmx12g/-Xmx4g -XX:MaxMetaspaceSize=1g/g' gradle.properties
sed -i 's/sun.rmi.transport=null/sun.rmi.transport=ALL-UNNAMED/g' gradle.properties
sed -i 's/#org.gradle.workers.max=4/org.gradle.workers.max=2/g' gradle.properties
sed -i 's/greclipse()/\/\/ greclipse()/g' build.gradle
sed -i 's/eclipse()\.configFile/\/\/ eclipse().configFile/g' build.gradle
if [ -d "buildSrc" ]; then
  find buildSrc -type f -name "*.gradle*" -exec sed -i 's/greclipse()/\/\/ greclipse()/g; s/eclipse()\.configFile/\/\/ eclipse().configFile/g' {} +
fi
git update-index --assume-unchanged gradle.properties build.gradle || true

echo "=== Configuración de Fineract 1.15 + PostgreSQL 18 completada con éxito ==="
