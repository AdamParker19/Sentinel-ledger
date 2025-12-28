# ==============================================================================
# Stage 1: Build
# ==============================================================================
FROM golang:1.21-alpine AS builder

# Install CA certificates and git for module download
RUN apk add --no-cache ca-certificates git

WORKDIR /app

# Copy all source files
COPY . .

# Download dependencies and build
RUN go mod tidy && \
    CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build \
    -ldflags="-w -s" \
    -o /sentinel-gateway \
    main.go

# ==============================================================================
# Stage 2: Runtime (Minimal distroless image)
# ==============================================================================
FROM gcr.io/distroless/static-debian12

# Copy the binary from builder
COPY --from=builder /sentinel-gateway /sentinel-gateway

# Copy CA certificates for Kafka TLS (if needed)
COPY --from=builder /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/

# Expose the gateway port
EXPOSE 3000

# Run the gateway
ENTRYPOINT ["/sentinel-gateway"]
