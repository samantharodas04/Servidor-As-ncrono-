JAVAC=javac
JAVA=java
OUT=out
SOURCES=$(shell find src -name "*.java")

.PHONY: compile run prepare-image prepare-overview check-tools clean clean-code

compile:
	mkdir -p $(OUT)
	$(JAVAC) -encoding UTF-8 -d $(OUT) $(SOURCES)

run: compile
	$(JAVA) -cp $(OUT) Main -port 8080

prepare-image: compile
	$(JAVA) -cp $(OUT) image.ImagePreparationTool $(IMAGE)

prepare-overview: compile
	$(JAVA) -cp $(OUT) image.ImagePreparationTool $(IMAGE) --overview-only

check-tools:
	@echo "Java:"
	@java -version
	@echo ""
	@echo "libvips:"
	@vips --version
	@echo ""
	@echo "vipsthumbnail:"
	@vipsthumbnail --version

clean-code:
	rm -rf $(OUT)

clean: clean-code
