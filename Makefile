JAVAC=javac
JAVA=java
OUT=out
SOURCES=$(shell find src -name "*.java")

compile:
	mkdir -p $(OUT)
	$(JAVAC) -encoding UTF-8 -d $(OUT) $(SOURCES)

run: compile
	$(JAVA) -cp $(OUT) Main -port 8080

prepare: compile
	$(JAVA) -cp $(OUT) image.PrepareImages 512

check-tools:
	@echo "Java:"
	@java -version
	@echo ""
	@echo "ImageMagick:"
	@if command -v magick >/dev/null 2>&1; then \
		magick -version; \
		echo ""; echo "Soporte PSB:"; \
		magick identify -list format | grep -E '^ *PSB' || (echo "PSB NO DISPONIBLE EN ESTA INSTALACION" && exit 1); \
	elif command -v convert >/dev/null 2>&1 && command -v identify >/dev/null 2>&1; then \
		convert -version; \
		echo ""; echo "Soporte PSB:"; \
		identify -list format | grep -E '^ *PSB' || (echo "PSB NO DISPONIBLE EN ESTA INSTALACION" && exit 1); \
	else \
		echo "ImageMagick no encontrado"; exit 1; \
	fi

clean-code:
	rm -rf $(OUT)

# ADVERTENCIA: elimina el cache de tiles; en una imagen de 24 GB obligaría a reprocesarla.
clean-tiles:
	rm -rf images/processed/* images/processed/.magick_tmp

clean: clean-code
