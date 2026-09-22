# Servidor asíncrono de imágenes por chunks

Proyecto Java 21 en reconstrucción incremental. El objetivo actual es mostrar
una imagen seleccionada mediante chunks JPEG generados bajo demanda, sin crear
una galería activa ni una pirámide completa de resoluciones en disco.

## Estado actual

El flujo nuevo ya incluye:

- descubrimiento de originales y lectura de dimensiones;
- niveles matemáticos de zoom;
- regiones visibles según centro y viewport;
- chunks fijos de hasta `512 × 512`;
- preparación regional con libvips;
- pool limitado de workers;
- generaciones y cancelación de solicitudes obsoletas;
- caché LRU en memoria limitada por bytes;
- generación exclusiva de los chunks que no estén en caché;
- mensaje binario `PAI/1 VIEW` documentado por su codec;
- servidor HTTP/WebSocket asíncrono en `/pai`;
- recepción de frames binarios y conversión a `ViewRequest`;
- un `ViewCoordinator` independiente por cliente WebSocket;
- procesamiento compartido con pool de workers y caché global;
- respuestas binarias `VIEW_START → CHUNK... → VIEW_END`;
- cola de escritura limitada e independiente por cliente;
- catálogo binario `LIST_IMAGES → IMAGE_LIST` con ID, nombre, dimensiones y bytes;
- rechazo de mensajes PAI inválidos sin detener el servidor.

`Main` inicia el servidor y el frontend permite enviar un `VIEW` válido o uno
inválido. El servidor procesa cada vista, envía sus chunks JPEG y el navegador
valida la generación, la cantidad de chunks y el total de bytes recibidos.
Después decodifica los JPEG y los dibuja en un canvas en las posiciones de
cada chunk.

La secuencia de respuesta utiliza números big-endian:

```text
VIEW_START = PAI/1, generación, viewport, zoom, región y cantidad
CHUNK      = PAI/1, generación, índice, geometría, longitud y JPEG
VIEW_END   = PAI/1, generación, cantidad y bytes JPEG totales
```

## Compilar y ejecutar

```bash
make compile
make run
```

Verificar herramientas:

```bash
make check-tools
```

## Directorios

```text
src/cache/    caché LRU
src/image/    lectura y procesamiento regional
src/view/     zoom, regiones, chunks, generaciones y coordinación
src/worker/   pool limitado de workers
src/protocol/ codec binario PAI/1 VIEW
src/server/   servidor HTTP/WebSocket y lectura de frames
web/          cliente mínimo para probar VIEW
```

Los originales se colocan en `images/originals`. `images/processed` no se usa
para construir una pirámide persistente.

La bitácora detallada del rediseño está en:

```text
../BITACORA_COMPLETA_SERVIDOR_ASINCRONO_CHUNKS.md
```

La explicación paso a paso de la arquitectura, WebSocket, TCP y los formatos
binarios PAI se encuentra en [docs/README.md](docs/README.md).

## Próximo hito

Agregar interacción de movimiento y zoom; por ahora la vista es fija y no
descarta chunks individualmente.
