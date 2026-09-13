# IRP/1.0 - Image Resolution Protocol

## 1. Objetivo

IRP/1.0 es el protocolo de aplicación del proyecto. Controla el listado de imágenes, consulta de metadatos y transferencia selectiva de tiles de una pirámide multirresolución.

La imagen original puede ser muy grande (incluyendo PSB). El cliente nunca solicita el archivo original completo.

## 2. Transporte

1. El frontend se obtiene inicialmente mediante HTTP/1.1.
2. El navegador realiza un Upgrade a WebSocket en `/irp`.
3. Los mensajes IRP/1.0 se transportan sobre esa conexión.
4. Los tiles JPEG se envían como frames binarios WebSocket.

WebSocket es el transporte. IRP/1.0 define los comandos, campos, estados y reglas de selección de datos.

## 3. Pirámide de resolución

Cada imagen preparada tiene `N` niveles. El nivel `N-1` es la resolución máxima y cada nivel anterior reduce aproximadamente a la mitad el ancho y alto.

Para el archivo de referencia `eso1242a.psb` (108199 x 81503 px) y tiles de 512 px, se generan aproximadamente 9 niveles, desde una vista reducida hasta la resolución original.

Cada nivel se divide en tiles:

```text
level8/
  0_0.jpg
  1_0.jpg
  2_0.jpg
  ...
```

El navegador calcula los tiles que intersectan el viewport y solicita solo esos archivos.

## 4. Formato

```text
IRP/1.0 COMANDO
Campo: valor
Campo: valor
```

## 5. Comandos

### LIST

Solicitud:

```text
IRP/1.0 LIST
```

Respuesta:

```text
IRP/1.0 200 OK
Count: 1
Image: eso1242a
```

### INFO

Solicitud:

```text
IRP/1.0 INFO
Image: eso1242a
```

Respuesta:

```text
IRP/1.0 200 OK
Image: eso1242a
Width: 108199
Height: 81503
Tile-Size: 512
Levels: 9
Source-Bytes: 26400000000
Processor: imagemagick-disk-tiles
```

### TILE

Solicitud:

```text
IRP/1.0 TILE
Image: eso1242a
Level: 8
X: 25
Y: 14
```

Respuesta de control:

```text
IRP/1.0 200 OK
Content-Type: image/jpeg
Content-Length: 42117
Image: eso1242a
Level: 8
X: 25
Y: 14
```

Después se envía un frame binario que contiene únicamente el JPEG de ese tile.

### HELLO

```text
IRP/1.0 HELLO
```

### CLOSE

```text
IRP/1.0 CLOSE
```

## 6. Estados

- `200 OK`: solicitud válida.
- `400 BAD REQUEST`: protocolo o comando inválido.
- `404 NOT FOUND`: imagen/tile inexistente.
- `409 INVALID REQUEST`: nivel o campos inválidos.
- `500 INTERNAL ERROR`: error interno.

## 7. Gestión de memoria

El cliente mantiene únicamente los `ImageBitmap` descargados para el nivel actual. Cuando cambia el nivel:

1. ejecuta `close()` sobre los bitmaps anteriores;
2. vacía el cache de tiles;
3. calcula el nuevo viewport;
4. solicita los tiles del nuevo nivel.

Por tanto existe transferencia y eliminación real de información; no es solamente una transformación CSS o un zoom de una imagen completa.

## 8. Procesamiento de imágenes gigantes

El servidor diferencia imágenes pequeñas de imágenes gigantes. Los archivos PSB/TIFF, los archivos mayores a un umbral de tamaño y los raster con dimensiones muy grandes se envían al procesador de disco.

El flujo es:

```text
archivo gigante
    |
    v
ImageMagick local (proceso iniciado por Java)
    |
    v
niveles + tiles JPEG en disco
    |
    v
ImageManager
    |
    v
IRP TILE
```

La preparación es cacheada. El servidor no vuelve a procesar el archivo si su tamaño y fecha de modificación no cambiaron y todos los niveles están completos.

## 9. Concurrencia

El servidor utiliza `AsynchronousServerSocketChannel` y `CompletionHandler` para aceptar, leer y escribir conexiones de forma asíncrona. Cada cliente mantiene su propia conexión WebSocket y puede solicitar tiles independientemente.

## 10. Referencias

- RFC 9110 - HTTP Semantics.
- RFC 9112 - HTTP/1.1.
- RFC 6455 - The WebSocket Protocol.
- ImageMagick Image Formats - PSB / Adobe Large Document Format.
- ESO, `eso1242a`, VISTA gigapixel mosaic.
