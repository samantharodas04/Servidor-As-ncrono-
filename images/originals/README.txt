COLOQUE AQUI LAS IMAGENES ORIGINALES.

Formatos reconocidos actualmente: JPG, JPEG, PNG, TIF, TIFF y PSB.

El flujo nuevo no ejecuta una preparacion exhaustiva ni crea una piramide en
images/processed. Main descubre los originales, calcula la vista solicitada y
genera bajo demanda solamente los chunks necesarios.

Desde la raiz del proyecto:

  make check-tools
  make run

La imagen usada por la demostracion actual se selecciona temporalmente mediante
la constante IMAGE_ID de src/Main.java. La seleccion llegara desde el cliente
cuando se conecte el siguiente hito WebSocket.
