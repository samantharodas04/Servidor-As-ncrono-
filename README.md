# Servidor asíncrono de imágenes

Visor de imágenes grandes desarrollado en Java 21. El navegador solicita una región mediante el protocolo binario **PAI/1** sobre un WebSocket persistente (`/pai`); el servidor procesa y envía únicamente los chunks necesarios, sin transferir el archivo original completo.

El visor permite mover y ampliar la imagen. Una vista nueva cancela la anterior. Los chunks de imagen se confirman con `CHUNK_ACK`; cuando el navegador conserva un bitmap compatible, puede anunciarlo con `VIEW_CACHED` y recibir `CHUNK_REF` sin retransmitir sus bytes. El servidor también conserva chunks codificados para evitar repetir su generación.

## Requisitos y ejecución

Se necesitan Java 21, GNU Make y libvips (`vips` y `vipsthumbnail`). Desde la raíz del proyecto:

```bash
make check-tools
make run
```

Abrir <http://localhost:8080/>. Los originales se colocan en `images/originals`. Algunas imágenes gigantes requieren preparación previa para acceder rápidamente a sus regiones:

```bash
make prepare-image IMAGE=ID_DE_LA_IMAGEN
```

Para generar únicamente la vista general: `make prepare-overview IMAGE=ID_DE_LA_IMAGEN`. La preparación conserva el original y crea derivados en `images/processed`; `make run` no la ejecuta automáticamente.

## Configuración y documento técnico

Las opciones del servidor están en `config/server.properties` y las del navegador en `web/config.js`. Reiniciar Java o recargar la página, respectivamente, después de cambiarlas.

La especificación completa de PAI/1 —formato de mensajes, algoritmos, cachés, concurrencia, decisiones de diseño y evidencia— se entrega en **`docs/PAI.pdf`**