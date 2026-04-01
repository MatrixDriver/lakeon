# ---- build stage ----
FROM golang:1.23-alpine AS builder

WORKDIR /app

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o log-collector .

# ---- runtime stage ----
FROM scratch

COPY --from=builder /app/log-collector /log-collector

# Non-root uid (numeric only — no /etc/passwd in scratch)
USER 65534

EXPOSE 9880

ENTRYPOINT ["/log-collector"]
