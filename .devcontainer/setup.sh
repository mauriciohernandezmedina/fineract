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

echo "=== 5. Configurando entorno SSH persistente y comandos globales (build-fineract / run-fineract) ==="
sudo tee /etc/profile.d/fineract-env.sh > /dev/null << 'EOF'
export JAVA_HOME="/usr/lib/jvm/msopenjdk-current"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_OPTS="-Xmx256m -Dorg.gradle.daemon=true"
export FINERACT_HIKARI_DRIVER_SOURCE_CLASS_NAME="org.postgresql.Driver"
export FINERACT_HIKARI_JDBC_URL="jdbc:postgresql://localhost:5432/fineract_tenants"
export FINERACT_HIKARI_USERNAME="postgres"
export FINERACT_HIKARI_PASSWORD="postgres"
export FINERACT_DEFAULT_TENANTDB_HOSTNAME="localhost"
export FINERACT_DEFAULT_TENANTDB_PORT="5432"
export FINERACT_DEFAULT_TENANTDB_UID="postgres"
export FINERACT_DEFAULT_TENANTDB_PWD="postgres"
export FINERACT_DEFAULT_TENANTDB_NAME="fineract_default"
export FINERACT_DEFAULT_TENANTDB_IDENTIFIER="default"
export FINERACT_SERVER_SSL_ENABLED="false"
export FINERACT_SERVER_PORT="8080"
export FINERACT_LIQUIBASE_ENABLED="true"
EOF
sudo chmod +x /etc/profile.d/fineract-env.sh

# Inyectar al inicio de ~/.bashrc y ~/.profile (antes del return de shells no interactivos) para que el Agente IA y SSH siempre lo carguen
for rc in "$HOME/.bashrc" "$HOME/.profile"; do
  if [ -f "$rc" ] && ! grep -q "fineract-env.sh" "$rc"; then
    tmp_rc=$(mktemp)
    echo '[ -f /etc/profile.d/fineract-env.sh ] && . /etc/profile.d/fineract-env.sh' > "$tmp_rc"
    cat "$rc" >> "$tmp_rc"
    mv "$tmp_rc" "$rc"
  fi
done

sudo tee /usr/local/bin/build-fineract > /dev/null << 'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ -f /etc/profile.d/fineract-env.sh ] && . /etc/profile.d/fineract-env.sh
cd /workspaces/fineract
echo "Compilando bootJar de Fineract (incremental con caché)..."
./gradlew :fineract-provider:bootJar \
  -x test -x cucumber \
  -x checkstyleMain -x checkstyleTest \
  -x spotbugsMain -x spotbugsTest \
  -x spotlessCheck -x spotlessApply \
  -x javadoc -x javadocJar \
  -x modernizer -x rat
EOF
sudo chmod +x /usr/local/bin/build-fineract

sudo tee /usr/local/bin/run-fineract > /dev/null << 'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ -f /etc/profile.d/fineract-env.sh ] && . /etc/profile.d/fineract-env.sh
cd /workspaces/fineract
sudo service postgresql start >/dev/null 2>&1 || true

JAR_FILE=$(ls /workspaces/fineract/fineract-provider/build/libs/*.jar 2>/dev/null | grep -v "plain" | head -n 1 || true)
if [ -z "$JAR_FILE" ]; then
  echo "No se encontró el JAR compilado. Ejecutando build-fineract primero..."
  build-fineract
  JAR_FILE=$(ls /workspaces/fineract/fineract-provider/build/libs/*.jar | grep -v "plain" | head -n 1)
fi

echo "Liberando memoria del daemon de Gradle antes de iniciar Fineract..."
./gradlew --stop >/dev/null 2>&1 || true

echo "Arrancando $JAR_FILE en http://localhost:${FINERACT_SERVER_PORT}/fineract-provider ..."
exec java \
  -Xmx1536m \
  -XX:MaxMetaspaceSize=512m \
  -XX:TieredStopAtLevel=1 \
  -XX:+UseStringDeduplication \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC \
  -Djava.security.egd=file:/dev/./urandom \
  --add-exports=java.naming/com.sun.jndi.ldap=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.security=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.management/javax.management=ALL-UNNAMED \
  --add-opens=java.naming/javax.naming=ALL-UNNAMED \
  -jar "$JAR_FILE"
EOF
sudo chmod +x /usr/local/bin/run-fineract

echo "=== Configuración de Fineract 1.15 + PostgreSQL 18 completada con éxito ==="
