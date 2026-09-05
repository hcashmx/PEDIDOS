# Configuración de desarrollo

## Requisitos

- JDK 21, que es la versión usada por las pruebas backend conservadas.
- PostgreSQL local.
- Gradle Wrapper para Kotlin.
- Maven Wrapper para backend.
- Node.js/npm para React.

El `pom.xml` configura compilación para Java 17, mientras las validaciones conservadas se ejecutaron con JDK 21. Esto describe niveles distintos de compilación y runtime; por sí solo no demuestra una incompatibilidad.

## PostgreSQL

Entorno ERP utilizado para las validaciones actuales:

```text
host: localhost
port: 5432
database: prueba
user: <DB_USER>
password: <DB_PASSWORD>
```

`BD\respaldo.sql` es una referencia histórica y no representa el esquema actual de la base de validación `prueba`.

El `application.properties` versionado del backend PEDIDOS apunta actualmente a otra base local y contiene credenciales directas. Por tanto, `prueba` fue el destino configurado para las validaciones, no el valor que hoy describe ese archivo versionado. Para desarrollo debe sustituirse localmente o, preferentemente, externalizarse sin versionar secretos.

Ejemplo conceptual:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/prueba
spring.datasource.username=<DB_USER>
spring.datasource.password=<DB_PASSWORD>
server.port=<BACKEND_PORT>
```

## Backend

El puerto es configurable. El archivo versionado usa 8080 y Kotlin conserva 8082 en `ServerConfig.BASE_IP`; 8082 fue un puerto temporal de pruebas. El puerto definitivo está pendiente de externalización y, para cualquier ejecución local, backend y clientes deben coincidir.

Comando habitual:

```powershell
cd "C:\BDS\PEDIDOS\Backend\effyFlow"
.\mvnw.cmd spring-boot:run
```

No ejecutar scripts o migraciones automáticamente sin autorización.

## Kotlin

`ServerConfig.BASE_IP`, en:

```text
App Pedidos\KotlinProject\composeApp\src\commonMain\kotlin\com\cyssoftware\effyflow\config\ServerConfig.kt
```

define actualmente el origen HTTP. El endpoint puede requerir ajuste local según puerto o IP del equipo. A futuro conviene externalizarlo por ambiente, argumentos, archivo local o variable de entorno.

Ejecución desktop:

```powershell
cd "C:\BDS\PEDIDOS\App Pedidos\KotlinProject"
.\gradlew.bat :composeApp:run
```

Credenciales de validación deben suministrarse como `<VENDEDOR_EMAIL>` y `<VENDEDOR_PASSWORD>`, nunca incorporarse al código.

## React

Configura `VITE_API_URL` sin credenciales:

```text
VITE_API_URL=http://localhost:<BACKEND_PORT>/api
```

Luego:

```powershell
cd "C:\BDS\PEDIDOS\Frontend Web\Web"
npm install
npm run dev
```
