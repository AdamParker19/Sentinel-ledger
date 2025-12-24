// Package main provides the entry point for the Sentinel Ingress Gateway.
// This is a high-performance HTTP ingestion service that accepts transaction
// requests and immediately pushes them to Kafka for async processing.
//
// Target: 10k+ requests/second
// Stack: Fiber (HTTP) + Sarama (Kafka AsyncProducer)
package main

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/IBM/sarama"
	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/recover"
	"github.com/gofiber/fiber/v2/middleware/requestid"
)

// =============================================================================
// Configuration
// =============================================================================

type Config struct {
	KafkaBrokers string
	IngressTopic string
	Port         string
}

func loadConfig() Config {
	return Config{
		KafkaBrokers: getEnv("KAFKA_BROKERS", "localhost:9092"),
		IngressTopic: getEnv("INGRESS_TOPIC", "raw.requests"),
		Port:         getEnv("PORT", "3000"),
	}
}

func getEnv(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

// =============================================================================
// Request/Response DTOs
// =============================================================================

// IngressRequest represents the incoming transaction payload.
// Only structural validation is performed here - business validation
// is delegated to downstream services.
type IngressRequest struct {
	Amount           float64 `json:"amount"`
	Currency         string  `json:"currency,omitempty"`
	MerchantID       string  `json:"merchantId"`
	CustomerID       string  `json:"customerId"`
	ClientRefID      string  `json:"clientReferenceId,omitempty"`
	Description      string  `json:"description,omitempty"`
	SourceIP         string  `json:"sourceIp,omitempty"`
	LocationCode     string  `json:"locationCode,omitempty"`
	IngestedAt       string  `json:"ingestedAt,omitempty"`
}

// IngressResponse is the acknowledgment returned to the client.
type IngressResponse struct {
	Status    string `json:"status"`
	Message   string `json:"message"`
	RequestID string `json:"requestId"`
}

// ErrorResponse provides structured error information.
type ErrorResponse struct {
	Error     string `json:"error"`
	Message   string `json:"message"`
	RequestID string `json:"requestId,omitempty"`
}

// =============================================================================
// Kafka Producer
// =============================================================================

type KafkaProducer struct {
	producer sarama.AsyncProducer
	topic    string
	logger   *slog.Logger
}

func newKafkaProducer(brokers []string, topic string, logger *slog.Logger) (*KafkaProducer, error) {
	config := sarama.NewConfig()
	
	// Performance tuning for high throughput
	config.Producer.RequiredAcks = sarama.WaitForLocal       // Only wait for leader ack
	config.Producer.Compression = sarama.CompressionSnappy   // Fast compression
	config.Producer.Flush.Frequency = 10 * time.Millisecond  // Batch messages
	config.Producer.Flush.Messages = 100                     // Batch up to 100 messages
	config.Producer.Return.Successes = false                 // Don't track successes (fire-and-forget)
	config.Producer.Return.Errors = true                     // Track errors for logging
	
	// Connection settings
	config.Net.DialTimeout = 10 * time.Second
	config.Net.ReadTimeout = 10 * time.Second
	config.Net.WriteTimeout = 10 * time.Second
	
	producer, err := sarama.NewAsyncProducer(brokers, config)
	if err != nil {
		return nil, fmt.Errorf("failed to create Kafka producer: %w", err)
	}

	kp := &KafkaProducer{
		producer: producer,
		topic:    topic,
		logger:   logger,
	}

	// Start error handler goroutine
	go kp.handleErrors()

	logger.Info("Kafka producer initialized",
		slog.String("brokers", strings.Join(brokers, ",")),
		slog.String("topic", topic))

	return kp, nil
}

func (kp *KafkaProducer) handleErrors() {
	for err := range kp.producer.Errors() {
		kp.logger.Error("Kafka producer error",
			slog.String("topic", err.Msg.Topic),
			slog.String("error", err.Err.Error()))
	}
}

func (kp *KafkaProducer) Send(key string, payload []byte) {
	msg := &sarama.ProducerMessage{
		Topic: kp.topic,
		Key:   sarama.StringEncoder(key),
		Value: sarama.ByteEncoder(payload),
	}
	kp.producer.Input() <- msg
}

func (kp *KafkaProducer) Close() error {
	kp.logger.Info("Closing Kafka producer...")
	return kp.producer.Close()
}

// =============================================================================
// HTTP Handlers
// =============================================================================

type Handler struct {
	producer *KafkaProducer
	logger   *slog.Logger
}

func newHandler(producer *KafkaProducer, logger *slog.Logger) *Handler {
	return &Handler{
		producer: producer,
		logger:   logger,
	}
}

// Ingress handles POST /api/v1/ingress
// It validates the request structure and pushes to Kafka immediately.
func (h *Handler) Ingress(c *fiber.Ctx) error {
	requestID := c.Locals("requestid").(string)
	
	// Parse JSON body
	var req IngressRequest
	if err := c.BodyParser(&req); err != nil {
		h.logger.Warn("Invalid JSON payload",
			slog.String("requestId", requestID),
			slog.String("error", err.Error()))
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error:     "INVALID_JSON",
			Message:   "Failed to parse JSON body",
			RequestID: requestID,
		})
	}

	// Validate required fields
	var validationErrors []string
	if req.Amount <= 0 {
		validationErrors = append(validationErrors, "amount must be positive")
	}
	if req.MerchantID == "" {
		validationErrors = append(validationErrors, "merchantId is required")
	}
	if req.CustomerID == "" {
		validationErrors = append(validationErrors, "customerId is required")
	}

	if len(validationErrors) > 0 {
		h.logger.Warn("Validation failed",
			slog.String("requestId", requestID),
			slog.Any("errors", validationErrors))
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error:     "VALIDATION_FAILED",
			Message:   strings.Join(validationErrors, "; "),
			RequestID: requestID,
		})
	}

	// Enrich with ingestion metadata
	req.IngestedAt = time.Now().UTC().Format(time.RFC3339Nano)
	if req.Currency == "" {
		req.Currency = "USD"
	}

	// Serialize to JSON for Kafka
	payload, err := json.Marshal(req)
	if err != nil {
		h.logger.Error("Failed to serialize request",
			slog.String("requestId", requestID),
			slog.String("error", err.Error()))
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error:     "SERIALIZATION_ERROR",
			Message:   "Failed to process request",
			RequestID: requestID,
		})
	}

	// Push to Kafka (fire-and-forget for max throughput)
	key := req.CustomerID // Use customerID as partition key for ordering
	h.producer.Send(key, payload)

	h.logger.Debug("Request ingested",
		slog.String("requestId", requestID),
		slog.String("customerId", req.CustomerID),
		slog.Float64("amount", req.Amount))

	// Return 202 Accepted immediately
	return c.Status(fiber.StatusAccepted).JSON(IngressResponse{
		Status:    "ACCEPTED",
		Message:   "Request queued for processing",
		RequestID: requestID,
	})
}

// Health handles GET /health
func (h *Handler) Health(c *fiber.Ctx) error {
	return c.JSON(fiber.Map{
		"status":    "healthy",
		"service":   "sentinel-gateway",
		"timestamp": time.Now().UTC().Format(time.RFC3339),
	})
}

// =============================================================================
// Application Setup
// =============================================================================

func setupApp(handler *Handler, logger *slog.Logger) *fiber.App {
	app := fiber.New(fiber.Config{
		// Performance settings
		Prefork:               false, // Set true for multi-core in production
		StrictRouting:         true,
		CaseSensitive:         true,
		DisableStartupMessage: false,
		
		// Error handling
		ErrorHandler: func(c *fiber.Ctx, err error) error {
			code := fiber.StatusInternalServerError
			if e, ok := err.(*fiber.Error); ok {
				code = e.Code
			}
			logger.Error("Unhandled error",
				slog.Int("status", code),
				slog.String("error", err.Error()))
			return c.Status(code).JSON(ErrorResponse{
				Error:   "INTERNAL_ERROR",
				Message: err.Error(),
			})
		},

		// Read/Write timeouts
		ReadTimeout:  5 * time.Second,
		WriteTimeout: 5 * time.Second,
		IdleTimeout:  120 * time.Second,
	})

	// Middleware
	app.Use(recover.New())
	app.Use(requestid.New())

	// Routes
	app.Get("/health", handler.Health)
	
	api := app.Group("/api/v1")
	api.Post("/ingress", handler.Ingress)

	return app
}

// =============================================================================
// Main Entry Point
// =============================================================================

func main() {
	// Initialize structured logger
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelDebug,
	}))
	slog.SetDefault(logger)

	logger.Info("==============================================")
	logger.Info("Sentinel Gateway - Starting up")
	logger.Info("==============================================")

	// Load configuration
	cfg := loadConfig()
	logger.Info("Configuration loaded",
		slog.String("kafkaBrokers", cfg.KafkaBrokers),
		slog.String("ingressTopic", cfg.IngressTopic),
		slog.String("port", cfg.Port))

	// Initialize Kafka producer
	brokers := strings.Split(cfg.KafkaBrokers, ",")
	producer, err := newKafkaProducer(brokers, cfg.IngressTopic, logger)
	if err != nil {
		logger.Error("Failed to initialize Kafka producer", slog.String("error", err.Error()))
		os.Exit(1)
	}

	// Initialize handlers and app
	handler := newHandler(producer, logger)
	app := setupApp(handler, logger)

	// Graceful shutdown
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		<-quit
		logger.Info("Shutdown signal received, initiating graceful shutdown...")
		
		// Shutdown Fiber
		if err := app.Shutdown(); err != nil {
			logger.Error("Error during Fiber shutdown", slog.String("error", err.Error()))
		}
		
		// Close Kafka producer
		if err := producer.Close(); err != nil {
			logger.Error("Error closing Kafka producer", slog.String("error", err.Error()))
		}
		
		logger.Info("Graceful shutdown complete")
	}()

	// Start server
	addr := fmt.Sprintf(":%s", cfg.Port)
	logger.Info("Starting HTTP server", slog.String("address", addr))
	if err := app.Listen(addr); err != nil {
		logger.Error("Server error", slog.String("error", err.Error()))
		os.Exit(1)
	}
}
