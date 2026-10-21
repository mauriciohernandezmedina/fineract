#!/usr/bin/env bash
set -euo pipefail

echo "=== 1. Instalando PostgreSQL 18 (requerido por Fineract 1.15) ==="
sudo rm -f /etc/apt/sources.list.d/yarn.list
sudo apt-get update
sudo apt-get install -y curl ca-certificates gnupg postgresql-common

# Registrar repositorio oficial PGDG para instalar PostgreSQL 18 en Debian Bookworm
if [ -x /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh ]; then
  sudo /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh -y
  sudo apt-get update
  sudo apt-get install -y postgresql-18 postgresql-client-18
else
  sudo apt-get install -y postgresql postgresql-contrib
fi

# Configurar pg_hba.conf para permitir conexiones locales por TCP y socket sin fricción
PG_HBA_FILES=$(sudo find /etc/postgresql -name "pg_hba.conf" 2>/dev/null || true)
for hba in $PG_HBA_FILES; do
  sudo sed -i 's/peer/trust/g; s/scram-sha-256/md5/g' "$hba"
done

sudo service postgresql restart

echo "=== 2. Inicializando roles (postgres y root) y bases de datos de Fineract ==="
sudo -u postgres psql -v ON_ERROR_STOP=1 <<-EOSQL
  ALTER USER postgres WITH SUPERUSER PASSWORD 'postgres';
  DO \$\$
  BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'root') THEN
      CREATE ROLE root WITH SUPERUSER LOGIN PASSWORD 'postgres';
    ELSE
      ALTER ROLE root WITH SUPERUSER LOGIN PASSWORD 'postgres';
    END IF;
  END
  \$\$;
EOSQL

sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname = 'fineract_tenants'" | grep -q 1 \
  || sudo -u postgres psql -c "CREATE DATABASE fineract_tenants OWNER postgres;"
sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname = 'fineract_default'" | grep -q 1 \
  || sudo -u postgres psql -c "CREATE DATABASE fineract_default OWNER postgres;"

sudo -u postgres psql -d fineract_tenants -c "GRANT ALL ON SCHEMA public TO postgres, root;"
sudo -u postgres psql -d fineract_default -c "GRANT ALL ON SCHEMA public TO postgres, root;"

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
if [ -d "buildSrc" ]; then
  find buildSrc -name "*.gradle*" -exec sed -i 's/greclipse()/\/\/ greclipse()/g' {} +
fi
git update-index --assume-unchanged gradle.properties build.gradle || true

echo "=== Configuración de Fineract 1.15 + PostgreSQL 18 completada con éxito ==="
