# Instalador Windows EffyFlow

## Generar una nueva versión

```powershell
.\build-msi.ps1 -Version 0.9.1
```

## Resultado

El MSI queda en:

```text
composeApp\build\compose\binaries\main\msi\EffyFlow-<VERSION>.msi
```

## Antes de distribuir

- comprobar número de versión;
- guardar SHA-256;
- probar primero en una PC;
- confirmar que SQLite/configuración/pedidos anteriores permanecen.

## Actualización

Instalar una versión nueva sobre la anterior debe conservar:

```text
%LOCALAPPDATA%\EffyFlow\effy.db
```

Por tanto conserva:

- configuración del servidor;
- catálogos;
- pedidos pendientes;
- estados de sincronización.

`upgradeUuid` NO debe cambiarse.

## Versión siguiente

Convención propuesta durante piloto:

```text
0.9.0
0.9.1
0.9.2
...
1.0.0 para primera versión estable.
```

## Advertencia

Mientras no exista firma digital, Windows puede mostrar Editor desconocido.
