# Detect OS
ifeq ($(OS),Windows_NT)
    # Windows (PowerShell)
    SOURCES = $(shell powershell -Command "Get-ChildItem -Recurse -Filter *.java | ForEach-Object { $$_.FullName }")
else
    # Mac/Linux
    SOURCES = $(shell find src -name "*.java")
endif

# Compile all Java files
compile:
	mkdir -p out
	javac -d out $(SOURCES)

# Run specific peer
run%:
	java -cp out peer.peerProcess $*

# Clean
clean:
	rm -rf out

.PHONY: compile clean
