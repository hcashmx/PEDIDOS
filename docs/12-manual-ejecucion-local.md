# Manual corto para correr Kotlin Pedidos y backend sync

Este manual es para levantar en desarrollo local:

- App Kotlin/Compose Desktop: `C:\BDS\PEDIDOS\App Pedidos\KotlinProject`
- Backend de sincronizacion: `C:\BDS\PEDIDOS\Backend\effyFlow`

No necesitas correr el ERP administrativo desde este manual. Si el ERP ya usa el puerto `8080`, levanta el backend sync en `8082`.

## 1. Requisitos

- IntelliJ IDEA.
- JDK 25 para backend sync instalado en:

```powershell
C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot
```

- JDK 21 para App Kotlin/Compose Desktop instalado en:

```powershell
C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot
```

- PostgreSQL disponible con la base que usa el backend.
- Puerto `8082` libre para el backend sync.

El backend actualmente tiene `server.port=8080` en `application.properties`, pero para este entorno conviene arrancarlo con override a `8082` porque `8080` puede estar ocupado por el ERP.

## 2. Orden recomendado

1. Verifica que PostgreSQL este levantado.
2. Levanta el backend sync en `8082`.
3. Levanta la app Kotlin Desktop.
4. En la app, configura la URL del backend como:

```text
http://localhost:8082
```

5. Prueba conexion desde la pantalla de configuracion.
6. Haz login.
7. Sincroniza catalogos.
8. Captura pedidos.
9. Sincroniza pedidos.

## 3. Correr el backend sync desde terminal

Abre PowerShell:

```powershell
cd "C:\BDS\PEDIDOS\Backend\effyFlow"
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:DB_PASSWORD="TU_PASSWORD_LOCAL"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082"
```

Si arranca bien, debe quedar escuchando en:

```text
http://localhost:8082
```

Para detenerlo, presiona `Ctrl+C` en esa terminal.

## 4. Correr el backend sync desde IntelliJ IDEA

1. Abre IntelliJ IDEA.
2. Selecciona `File > Open`.
3. Abre la carpeta:

```text
C:\BDS\PEDIDOS\Backend\effyFlow
```

4. Espera a que IntelliJ importe Maven.
5. Configura el JDK del proyecto:
   `File > Project Structure > Project SDK > JDK 25`.
6. Abre la clase:

```text
src/main/java/com/cyssoftware/effyFlow/EffyFlowApplication.java
```

7. Crea una configuracion Spring Boot o ejecuta el boton verde sobre `main`.
8. En esa configuracion agrega como argumento de programa:

```text
--server.port=8082
```

9. Ejecuta la configuracion.

Si el puerto `8082` no responde, revisa que la aplicacion siga corriendo y que no haya fallado la conexion a PostgreSQL.

## 5. Correr la app Kotlin desde terminal

Abre otra terminal PowerShell:

```powershell
cd "C:\BDS\PEDIDOS\App Pedidos\KotlinProject"
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :composeApp:run
```

Esto abre la aplicacion Desktop de Pedidos.

Para detenerla, cierra la ventana de la app o presiona `Ctrl+C` en la terminal.

## 6. Correr la app Kotlin desde IntelliJ IDEA

1. Abre IntelliJ IDEA.
2. Selecciona `File > Open`.
3. Abre la carpeta:

```text
C:\BDS\PEDIDOS\App Pedidos\KotlinProject
```

4. Espera a que IntelliJ importe Gradle.
5. Configura el JDK:
   `File > Project Structure > Project SDK > JDK 21`.
6. Verifica que Gradle use el mismo JDK:
   `Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JVM`.
7. En la ventana de Gradle, ejecuta:

```text
KotlinProject > composeApp > Tasks > compose desktop > run
```

Si no aparece esa tarea, puedes crear una configuracion Gradle manual con:

```text
:composeApp:run
```

El punto de entrada desktop es:

```text
composeApp/src/jvmMain/kotlin/com/cyssoftware/effyflow/main.kt
```

## 7. Configurar la URL del backend en la app

La app ya no debe requerir recompilar para cambiar servidor.

Al primer arranque, si no hay URL guardada, la app muestra la pantalla de configuracion del servidor.

Usa:

```text
http://localhost:8082
```

Luego:

1. Presiona `Probar conexion`.
2. Si aparece conectado, presiona `Guardar`.
3. Continua al login.

Si cambia el servidor, entra a `Configuracion` desde la app y guarda la nueva URL.

## 8. Comandos utiles de verificacion

Compilar Kotlin sin abrir la app:

```powershell
cd "C:\BDS\PEDIDOS\App Pedidos\KotlinProject"
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :composeApp:compileKotlinJvm
```

Correr prueba focal Kotlin:

```powershell
cd "C:\BDS\PEDIDOS\App Pedidos\KotlinProject"
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :composeApp:jvmTest --tests com.cyssoftware.effyflow.PedidoCreditoRulesTest
```

Correr prueba focal backend:

```powershell
cd "C:\BDS\PEDIDOS\Backend\effyFlow"
.\mvnw.cmd -Dtest=PedidoRenglonContractUnitTest test
```

## 9. Problemas comunes

### El login da HTTP 403

Normalmente significa que estas entrando al servicio equivocado o al puerto equivocado.

- ERP administrativo: `http://localhost:8080`
- Backend sync para Pedidos: `http://localhost:8082`

Configura la app Kotlin con `http://localhost:8082`.

### El puerto 8082 no responde

Revisa que el backend sync siga corriendo. Si lo levantaste desde terminal, debe seguir abierta la ventana de PowerShell.

Tambien revisa que no haya fallado por conexion a PostgreSQL.

### La app no sincroniza

Verifica en este orden:

1. Backend sync corriendo en `8082`.
2. URL guardada en la app: `http://localhost:8082`.
3. Login correcto.
4. Catalogos sincronizados antes de capturar.
5. Al enviar pedidos, revisar mensajes de error en la pantalla de sincronizacion.

### IntelliJ no reconoce Gradle o Maven

Cierra y vuelve a abrir la carpeta correcta:

- Para Kotlin: `C:\BDS\PEDIDOS\App Pedidos\KotlinProject`
- Para backend: `C:\BDS\PEDIDOS\Backend\effyFlow`

No abras directamente `composeApp` ni `src`.

## 10. Resumen rapido

Terminal 1, backend:

```powershell
cd "C:\BDS\PEDIDOS\Backend\effyFlow"
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:DB_PASSWORD="TU_PASSWORD_LOCAL"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082"
```

Terminal 2, app Kotlin:

```powershell
cd "C:\BDS\PEDIDOS\App Pedidos\KotlinProject"
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat :composeApp:run
```

En la app:

```text
Servidor / URL base: http://localhost:8082
```
