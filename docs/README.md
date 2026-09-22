# Guía de arquitectura y protocolo PAI/1

Este documento explica cómo está construido el servidor asíncrono de imágenes,
por qué se tomaron las decisiones actuales y cómo viaja una vista desde el
navegador hasta Java y de regreso.

El objetivo es que una persona que abra el proyecto por primera vez pueda
entenderlo paso a paso sin tener que comenzar leyendo todas las clases.

## 1. Qué solicita el proyecto

El enunciado oficial pide principalmente:

- un servidor asíncrono en Java 20 o 21;
- atención de múltiples clientes;
- HTTP para entregar inicialmente HTML, JavaScript y CSS;
- un protocolo propio para controlar la resolución y transmisión de imágenes;
- transferencia selectiva, sin enviar la imagen gigante completa;
- procesamiento y selección de la información del lado del servidor;
- funcionamiento sin depender de servicios externos.

Nuestro protocolo propio se llama:

```text
PAI/1 = Protocolo Asíncrono de Imágenes, versión 1
```

## 2. La idea general

El navegador no solicita la imagen completa. Solicita solamente una vista:

```text
imagen + zoom + centro + tamaño de la ventana
```

Java calcula qué región se necesita, la divide en chunks de hasta `512 × 512`,
genera únicamente los JPEG faltantes y los devuelve de forma independiente.

```text
┌──────────────────┐        VIEW         ┌─────────────────────┐
│ Navegador        │ ──────────────────> │ Servidor Java       │
│ HTML + app.js    │                     │ AsyncHttpServer     │
└──────────────────┘                     └──────────┬──────────┘
          ▲                                         │
          │                                         v
          │                              ┌─────────────────────┐
          │                              │ ViewCoordinator     │
          │                              │ una sesión/cliente  │
          │                              └──────────┬──────────┘
          │                                         │
          │                                         v
          │                              ┌─────────────────────┐
          │                              │ ViewProcessor       │
          │                              │ zoom, región, caché │
          │                              └──────────┬──────────┘
          │                                         │
          │                                         v
          │                              ┌─────────────────────┐
          │                              │ libvips + workers   │
          │                              │ generan JPEG       │
          │                              └──────────┬──────────┘
          │                                         │
          └──── VIEW_START + CHUNK... + VIEW_END ───┘
```

## 3. WebSocket, PAI y TCP no son lo mismo

Actualmente sí usamos TCP, pero no implementamos internamente un algoritmo TCP.

Las capas reales son:

```text
┌──────────────────────────────────────────────────────────────┐
│ Aplicación del proyecto                                     │
│ PAI/1: VIEW, CHUNK, generaciones, coordenadas y catálogo    │
├──────────────────────────────────────────────────────────────┤
│ WebSocket                                                   │
│ Delimita mensajes binarios y permite comunicación continua  │
├──────────────────────────────────────────────────────────────┤
│ TCP, administrado por navegador, Java y sistema operativo   │
│ Orden, ACK, retransmisión, ventana y control de congestión   │
├──────────────────────────────────────────────────────────────┤
│ IP y red física                                             │
└──────────────────────────────────────────────────────────────┘
```

### Qué implementa nuestro código

- handshake WebSocket sobre una solicitud HTTP;
- lectura y escritura de frames WebSocket;
- protocolo binario PAI dentro de esos frames;
- generaciones para descartar vistas antiguas;
- cola de salida limitada por cliente;
- procesamiento, caché y geometría de chunks.

### Qué no implementa nuestro código

- ACK de TCP;
- números de secuencia TCP;
- retransmisiones por pérdida de paquetes;
- ventana deslizante TCP;
- algoritmos de congestión como Reno o CUBIC;
- fragmentación IP.

Todo eso lo realiza la pila TCP del sistema operativo. Java utiliza
`AsynchronousServerSocketChannel` y `AsynchronousSocketChannel`; esos sockets
son TCP reales.

Dos conceptos pueden parecerse, pero no deben confundirse:

```text
generationId de PAI  ≠ número de secuencia TCP
cola de ClientSession ≠ ventana o control de congestión TCP
```

`generationId` indica cuál vista sigue siendo útil para la aplicación. La cola
evita escrituras WebSocket simultáneas y limita nuestra memoria. Ninguno de los
dos reemplaza las funciones de TCP.

## 4. Por qué usamos WebSocket

Un navegador sí puede usar HTTP y WebSocket, pero no puede abrir libremente un
socket TCP crudo desde JavaScript.

El flujo es:

```text
1. El navegador abre una conexión al servidor.
2. Solicita GET / mediante HTTP.
3. Java entrega index.html, app.js y style.css.
4. app.js solicita GET /pai con Upgrade: websocket.
5. El servidor responde 101 Switching Protocols.
6. La misma conexión TCP queda funcionando como WebSocket.
7. Los payloads binarios de WebSocket contienen mensajes PAI.
```

Representación de un envío:

```text
segmentos TCP
    └── bytes de un frame WebSocket
            └── payload binario PAI
                    └── cabecera + campos + JPEG opcional
```

TCP puede dividir un frame grande en varios segmentos. Eso no cambia el mensaje
PAI: WebSocket reconstruye el mensaje antes de entregarlo al receptor.

## 5. Evolución del proyecto paso a paso

### Paso 1: separar originales y vistas

Los archivos originales permanecen en `images/originals`. No se envían
completos ni se crea obligatoriamente una pirámide persistente por cada imagen.

### Paso 2: descubrir las imágenes

`ImageCatalog` inspecciona los originales y construye objetos `ImageSource` con:

```text
ID, nombre, ruta, ancho, alto, tamaño y fecha de modificación
```

### Paso 3: calcular zoom y región

`ZoomCalculator` calcula escalas permitidas. `ChunkPlanner` transforma:

```text
centro + viewport + zoom
```

en una región visible y un conjunto estable de chunks anclados a una cuadrícula
global.

### Paso 4: procesar solamente la región necesaria

`VipsViewPreparer` usa libvips para preparar temporalmente la región requerida.
Después `ChunkWorkerPool` genera los JPEG mediante una cantidad limitada de
workers.

```text
original gigante
      │
      v
región temporal reducida
      │
      ├── chunk JPEG 0
      ├── chunk JPEG 1
      ├── chunk JPEG 2
      └── chunk JPEG N
```

El archivo temporal se elimina cuando termina la preparación.

### Paso 5: agregar caché

`ChunkCache` es una caché LRU limitada a 32 MiB. Su clave identifica de manera
estable:

```text
imagen + versión del archivo + zoom + nivel + chunkSize + columna + fila
```

Una nueva vista reutiliza los JPEG existentes y genera solamente los faltantes.

### Paso 6: agregar generaciones y cancelación

Cada cliente posee un `ViewCoordinator`. Si el mismo cliente solicita una
generación más reciente, se cancela el trabajo anterior:

```text
VIEW generación 10 ── trabajando
VIEW generación 11 ── llega después
                      └── cancela generación 10
```

Dos clientes diferentes sí pueden usar ambos `generationId=1`, porque cada uno
tiene su propio coordinador.

### Paso 7: crear PAI/1 y el servidor WebSocket

Se añadió una cabecera binaria común, opcodes con nombre, validaciones de
longitud y el endpoint WebSocket `/pai`.

### Paso 8: solicitar el catálogo

Al conectar, el navegador pide la información disponible:

```text
Navegador                         Servidor
    │                                │
    │──── LIST_IMAGES ──────────────>│
    │<─── IMAGE_LIST ────────────────│
    │     ID, nombre, ancho, alto,   │
    │     tamaño del archivo         │
```

No se devuelve la imagen completa en este paso.

### Paso 9: solicitar y procesar una vista

El navegador construye un `VIEW`. El servidor lo decodifica como `ViewRequest`
y lo entrega al coordinador de esa conexión.

```text
VIEW
  │
  v
ViewMessageCodec.decode
  │
  v
ViewRequest
  │
  v
ViewCoordinator
  │
  v
ViewProcessor
  ├── selecciona ImageSource
  ├── calcula ZoomLevel
  ├── crea StableChunkPlan
  ├── busca cada chunk en caché
  ├── prepara la región si faltan chunks
  ├── ejecuta workers
  └── produce ViewResult
```

### Paso 10: enviar los chunks

El `ViewResult` se convierte en esta secuencia:

```text
VIEW_START
CHUNK 0
CHUNK 1
...
CHUNK N
VIEW_END
```

Cada mensaje PAI ocupa un frame WebSocket binario. Una cola FIFO por cliente
garantiza que solo exista una escritura activa sobre su socket.

## 6. Cabecera común PAI/1

Todos los mensajes comienzan con cinco bytes:

```text
Offset   Bytes   Significado
0        0x50    P
1        0x41    A
2        0x49    I
3        0x01    versión 1
4        variable opcode
```

Diagrama:

```text
+------+------+------+------+--------+
|  P   |  A   |  I   | ver. | opcode |
| 0x50 | 0x41 | 0x49 | 0x01 |  1..6  |
+------+------+------+------+--------+
  byte0  byte1  byte2  byte3  byte4
```

Todos los enteros de varios bytes se codifican en big-endian.

## 7. Operaciones actuales

```text
Opcode  Nombre        Dirección              Función
1       VIEW          navegador -> servidor  solicitar una vista
2       LIST_IMAGES   navegador -> servidor  solicitar catálogo
3       IMAGE_LIST    servidor -> navegador  devolver catálogo
4       VIEW_START    servidor -> navegador  iniciar una respuesta
5       CHUNK         servidor -> navegador  entregar un JPEG
6       VIEW_END      servidor -> navegador  finalizar/verificar
```

## 8. Formato de VIEW

```text
Offset   Tamaño   Campo
0..4     5        cabecera PAI/1, opcode 1
5..12    8        generationId
13..16   4        zoomIndex
17..20   4        centerX
21..24   4        centerY
25..28   4        viewportWidth
29..32   4        viewportHeight
33..34   2        longitud de imageId
35..     variable imageId UTF-8
```

`chunkSize` no lo decide el navegador. El servidor agrega actualmente el valor
`512` al construir `ViewRequest`.

## 9. Formato de VIEW_START

Longitud fija: 45 bytes.

```text
Offset   Tamaño   Campo
0..4     5        cabecera PAI/1, opcode 4
5..12    8        generationId
13..16   4        viewportWidth
17..20   4        viewportHeight
21..24   4        zoomIndex
25..28   4        regionX del original
29..32   4        regionY del original
33..36   4        regionWidth del original
37..40   4        regionHeight del original
41..44   4        cantidad esperada de chunks
```

Este mensaje permite al navegador preparar el estado de recepción antes de que
lleguen los JPEG.

## 10. Formato de CHUNK

Metadatos fijos: 45 bytes. Después se agregan los bytes JPEG.

```text
Offset   Tamaño   Campo
0..4     5        cabecera PAI/1, opcode 5
5..12    8        generationId
13..16   4        índice del chunk
17..20   4        columna global
21..24   4        fila global
25..28   4        canvasX con signo
29..32   4        canvasY con signo
33..36   4        outputWidth
37..40   4        outputHeight
41..44   4        jpegLength
45..     variable JPEG
```

Visualmente:

```text
┌────────────── metadatos PAI ──────────────┬──────── JPEG ────────┐
│ gen | índice | fila/col | x/y | w/h | len │ FF D8 ... ... FF D9 │
└────────────────────────────────────────────┴──────────────────────┘
```

`canvasX` o `canvasY` pueden ser negativos cuando un tile sobrepasa parcialmente
el borde visible. Esto es válido: el canvas recortará la parte exterior.

## 11. Formato de VIEW_END

Longitud fija: 25 bytes.

```text
Offset   Tamaño   Campo
0..4     5        cabecera PAI/1, opcode 6
5..12    8        generationId
13..16   4        cantidad total de chunks
17..24   8        suma de bytes JPEG
```

El navegador compara esos valores con lo recibido. Así detecta chunks faltantes,
duplicados o una suma de bytes inconsistente.

## 12. Ejemplo de una vista completa

Para una vista de demostración se obtuvo:

```text
VIEW generationId=1, viewport=1100x650

Servidor -> VIEW_START, chunks esperados=4
Servidor -> CHUNK índice=0
Servidor -> CHUNK índice=1
Servidor -> CHUNK índice=2
Servidor -> CHUNK índice=3
Servidor -> VIEW_END, chunks=4, jpegBytes=65204
```

El intercambio completo se ve así:

```text
Navegador                                             Servidor
    │                                                    │
    │──── PAI VIEW, generación 1 ───────────────────────>│
    │                                                    │ procesa
    │<─── PAI VIEW_START, espera 4 ─────────────────────│
    │<─── PAI CHUNK 0, geometría + JPEG ────────────────│
    │<─── PAI CHUNK 1, geometría + JPEG ────────────────│
    │<─── PAI CHUNK 2, geometría + JPEG ────────────────│
    │<─── PAI CHUNK 3, geometría + JPEG ────────────────│
    │<─── PAI VIEW_END, 4 chunks, 65204 bytes ──────────│
    │                                                    │
    │ valida generación, índices, JPEG, cantidad y bytes │
```

## 13. Concurrencia y límites

### Por cliente

Cada `ClientSession` posee:

- un `ViewCoordinator`;
- una cola FIFO de mensajes pendientes;
- una sola escritura WebSocket activa;
- un máximo de 32 MiB en la cola.

### Compartido entre clientes

- catálogo de imágenes;
- `ViewProcessor`;
- dos workers de chunks;
- caché LRU de 32 MiB.

```text
Cliente A ── coordinador A ──┐
                             ├── procesador + workers + caché
Cliente B ── coordinador B ──┘
```

La cola de aplicación evita `WritePendingException` y limita el crecimiento de
memoria. TCP mantiene además su propio control de flujo y congestión, separado
de esta cola.

## 14. Qué hace actualmente el navegador

`web/app.js` ya puede:

- abrir WebSocket;
- solicitar y mostrar el catálogo;
- construir un `VIEW` binario;
- reconocer `VIEW_START`, `CHUNK` y `VIEW_END`;
- rechazar firma, versión, opcode o longitud incorrectos;
- verificar inicio y final de cada JPEG;
- detectar índices duplicados o fuera de rango;
- comprobar la cantidad y la suma total de bytes;
- ignorar respuestas de una generación que ya no es la solicitada;
- decodificar los JPEG y dibujarlos en un canvas según `canvasX` y `canvasY`;
- esperar a que terminen de dibujarse antes de marcar la vista como completa.

También muestra el progreso en texto:

```text
VIEW 1: 3/4 chunks
VIEW 1 completa: 4 chunks, 63.7 KiB
```

## 15. Qué falta

El canvas muestra una vista fija de 1100 × 650. Los chunks que sobresalen se
recortan automáticamente; el navegador libera cada bitmap temporal después de
dibujarlo. Aún faltan los controles de movimiento y zoom, así como la gestión
individual de chunks durante el desplazamiento.

## 16. Archivos principales

```text
src/Main.java                         arranque del servidor
src/server/AsyncHttpServer.java       HTTP, WebSocket y sesiones
src/server/WebSocketUtil.java         handshake y frames WebSocket
src/protocol/PaiProtocol.java         cabecera común PAI/1
src/protocol/PaiOpcode.java           nombres de operaciones
src/protocol/ImageCatalogCodec.java   LIST_IMAGES e IMAGE_LIST
src/protocol/ViewMessageCodec.java    VIEW
src/protocol/ViewResponseCodec.java   VIEW_START, CHUNK y VIEW_END
src/view/ViewCoordinator.java         generación activa por cliente
src/view/ViewProcessor.java           procesamiento completo de la vista
src/view/ChunkPlanner.java            geometría estable de chunks
src/cache/ChunkCache.java             caché LRU por bytes
src/worker/ChunkWorkerPool.java       workers limitados
web/app.js                            codec y estado del navegador
```

## 17. Resumen corto

```text
HTTP entrega la aplicación web.
WebSocket mantiene una comunicación binaria continua.
PAI define qué significan los bytes de la aplicación.
TCP transporta esos bytes de forma ordenada y confiable.
Java selecciona y genera solamente los chunks necesarios.
```

No existe un algoritmo TCP propio en el proyecto. Existe un protocolo de
aplicación propio, PAI/1, que utiliza WebSocket sobre TCP.
