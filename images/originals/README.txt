COLOQUE AQUI LAS IMAGENES FUENTE.

Formatos reconocidos: JPG, JPEG, PNG, TIF, TIFF y PSB.

Para la imagen gigante del proyecto:
  eso1242a.psb

Copiela dentro de esta carpeta y ejecute desde la raiz del proyecto:
  make check-tools
  make prepare
  make run

IMPORTANTE:
- PSB/TIFF gigante se procesa con ImageMagick instalado localmente.
- La primera preparacion puede tardar y utilizar bastante espacio en disco.
- Despues se reutiliza images/processed y el servidor arranca rapidamente.
- No ejecute make clean-tiles si no desea volver a procesar el PSB.
