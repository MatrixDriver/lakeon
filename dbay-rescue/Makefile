.PHONY: build build-all test test-e2e clean release vet fmt

VERSION := $(shell git describe --tags --always --dirty 2>/dev/null || echo dev)
LDFLAGS := -X main.version=$(VERSION) -s -w

build:
	go build -ldflags "$(LDFLAGS)" -o dbay-rescue ./cmd/dbay-rescue

build-all: clean
	mkdir -p dist
	GOOS=linux  GOARCH=amd64 go build -ldflags "$(LDFLAGS)" -o dist/dbay-rescue-linux-amd64  ./cmd/dbay-rescue
	GOOS=linux  GOARCH=arm64 go build -ldflags "$(LDFLAGS)" -o dist/dbay-rescue-linux-arm64  ./cmd/dbay-rescue
	GOOS=darwin GOARCH=arm64 go build -ldflags "$(LDFLAGS)" -o dist/dbay-rescue-darwin-arm64 ./cmd/dbay-rescue
	GOOS=darwin GOARCH=amd64 go build -ldflags "$(LDFLAGS)" -o dist/dbay-rescue-darwin-amd64 ./cmd/dbay-rescue
	cd dist && shasum -a 256 dbay-rescue-* > SHA256SUMS

test:
	go test ./...

test-e2e:
	go test -tags=e2e ./test/...

vet:
	go vet ./...

fmt:
	gofmt -w .

clean:
	rm -rf dist dbay-rescue

release: build-all
	@echo "Release artifacts in dist/:"
	@ls -lh dist/
