COLOQUE AQUI LAS IMAGENES ORIGINALES.

Formatos reconocidos: JPG, JPEG, PNG, TIF, TIFF, PSB, WEBP y AVIF.

Main descubre los originales y genera bajo demanda solo los chunks solicitados.
Para fuentes no JPEG gigantes puede prepararse una vista general y un BigTIFF
mosaico de resolucion completa en images/processed; no es una piramide.

Desde la raiz del proyecto:

  make check-tools
  make prepare-image IMAGE=ID  # solo si una fuente gigante lo necesita
  make prepare-overview IMAGE=ID  # solo vista general
  make run

La imagen se selecciona desde el navegador. PSB requiere que libvips pueda
abrir el archivo concreto; el nombre de extension no basta.
