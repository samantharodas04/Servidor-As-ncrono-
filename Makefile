JAVAC=javac
JAVA=java
OUT=out
SOURCES=$(shell find src -name "*.java")

.PHONY: compile run check-tools clean clean-code

compile:
	mkdir -p $(OUT)
	$(JAVAC) -encoding UTF-8 -d $(OUT) $(SOURCES)

run: compile
	$(JAVA) -cp $(OUT) Main -port 8080

check-tools:
	@echo "Java:"
	@java -version
	@echo ""
	@echo "libvips:"
	@vips --version

clean-code:
	rm -rf $(OUT)

clean: clean-code
